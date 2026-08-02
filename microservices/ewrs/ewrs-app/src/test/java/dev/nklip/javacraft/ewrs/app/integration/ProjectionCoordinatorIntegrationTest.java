package dev.nklip.javacraft.ewrs.app.integration;

import dev.nklip.javacraft.ewrs.api.command.ApproveWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CreateWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.RejectWorkRequest;
import dev.nklip.javacraft.ewrs.api.query.BudgetProjectionResponse;
import dev.nklip.javacraft.ewrs.api.query.RebuildProjectionsResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.app.AbstractPostgresIntegrationTest;
import dev.nklip.javacraft.ewrs.app.service.WorkRequestCommandService;
import dev.nklip.javacraft.ewrs.app.repository.BudgetProjectionRepository;
import dev.nklip.javacraft.ewrs.app.repository.ProjectionCheckpointRepository;
import dev.nklip.javacraft.ewrs.app.repository.WorkRequestProjectionRepository;
import dev.nklip.javacraft.ewrs.app.service.ProjectionCoordinator;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProjectionCoordinatorIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WorkRequestCommandService workRequestCommandService;

    @Autowired
    private ProjectionCoordinator projectionCoordinator;

    @Autowired
    private WorkRequestProjectionRepository workRequestProjectionRepository;

    @Autowired
    private BudgetProjectionRepository budgetProjectionRepository;

    @Autowired
    private ProjectionCheckpointRepository projectionCheckpointRepository;

    @Test
    void catchUpAppliesUnreadEventsInOrderAndStaysIdempotent() {
        WorkRequestResponse created = workRequestCommandService.create(new CreateWorkRequest(
                "Ship new JDBC docs",
                Priority.CRITICAL,
                "PLATFORM-2026",
                40,
                "Nikita",
                "corr-create"
        ));
        workRequestCommandService.approve(created.requestId(), new ApproveWorkRequest("Lead", "corr-approve"));

        projectionCoordinator.catchUpFromLastApplied();

        WorkRequestResponse projected = workRequestProjectionRepository.findByRequestId(created.requestId())
                .orElseThrow();
        BudgetProjectionResponse budget = budgetProjectionRepository.findByBudgetCode("PLATFORM-2026")
                .orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(EventStatus.ACCEPTED, projected.status()),
                () -> Assertions.assertEquals("Nikita", projected.requestedBy()),
                () -> Assertions.assertEquals("Lead", projected.lastActor()),
                () -> Assertions.assertEquals(2L, projected.streamVersion()),
                () -> Assertions.assertEquals(40, budget.reservedAmount()),
                () -> Assertions.assertEquals(210, budget.remainingBudget()),
                () -> Assertions.assertEquals(2L, projectionCheckpointRepository.getLastAppliedEventId(ProjectionCoordinator.PROJECTION_NAME)),
                () -> Assertions.assertEquals(1, eventsMonitor.getStorage().size()),
                () -> Assertions.assertEquals(EventStatus.ACCEPTED, eventsMonitor.getStorage().getFirst().getStatus())
        );

        projectionCoordinator.catchUpFromLastApplied();

        BudgetProjectionResponse budgetAfterSecondCatchUp = budgetProjectionRepository.findByBudgetCode("PLATFORM-2026")
                .orElseThrow();
        Assertions.assertEquals(40, budgetAfterSecondCatchUp.reservedAmount());
    }

    @Test
    void rebuildRecreatesTheSameProjectionStateFromEventHistory() {
        WorkRequestResponse first = workRequestCommandService.create(new CreateWorkRequest(
                "Backfill customer audit table",
                Priority.MAJOR,
                "OPS-2026",
                30,
                "Nikita",
                "corr-1"
        ));
        workRequestCommandService.approve(first.requestId(), new ApproveWorkRequest("Lead", "corr-2"));

        WorkRequestResponse second = workRequestCommandService.create(new CreateWorkRequest(
                "Replatform sidecar agent",
                Priority.NORMAL,
                "RND-2026",
                15,
                "Nikita",
                "corr-3"
        ));
        workRequestCommandService.reject(second.requestId(), new RejectWorkRequest(
                "Operator",
                "Waiting for architecture sign-off",
                "corr-4"
        ));

        RebuildProjectionsResponse rebuild = projectionCoordinator.rebuildProjections();
        List<WorkRequestResponse> projectedRequests = workRequestProjectionRepository.findAll(null, null, null);
        BudgetProjectionResponse opsBudget = budgetProjectionRepository.findByBudgetCode("OPS-2026").orElseThrow();
        BudgetProjectionResponse rndBudget = budgetProjectionRepository.findByBudgetCode("RND-2026").orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(4, rebuild.eventsReplayed()),
                () -> Assertions.assertEquals(2, rebuild.requestsProjected()),
                () -> Assertions.assertEquals(3, rebuild.budgetsProjected()),
                () -> Assertions.assertEquals(2, projectedRequests.size()),
                () -> Assertions.assertTrue(projectedRequests.stream()
                        .anyMatch(request -> request.requestId() == first.requestId() && request.status() == EventStatus.ACCEPTED)),
                () -> Assertions.assertTrue(projectedRequests.stream()
                        .anyMatch(request -> request.requestId() == second.requestId()
                                && request.status() == EventStatus.REJECTED
                                && "Waiting for architecture sign-off".equals(request.reason()))),
                () -> Assertions.assertEquals(30, opsBudget.reservedAmount()),
                () -> Assertions.assertEquals(90, opsBudget.remainingBudget()),
                () -> Assertions.assertEquals(0, rndBudget.reservedAmount()),
                () -> Assertions.assertEquals(180, rndBudget.remainingBudget())
        );
    }
}
