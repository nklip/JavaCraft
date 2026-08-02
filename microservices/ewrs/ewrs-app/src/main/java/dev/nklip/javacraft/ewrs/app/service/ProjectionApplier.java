package dev.nklip.javacraft.ewrs.app.service;

import dev.nklip.javacraft.ewrs.api.query.BudgetProjectionResponse;
import dev.nklip.javacraft.ewrs.api.query.ProjectionUpdateResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.app.repository.BudgetProjectionRepository;
import dev.nklip.javacraft.ewrs.app.repository.ProjectionCheckpointRepository;
import dev.nklip.javacraft.ewrs.app.repository.WorkRequestProjectionRepository;
import dev.nklip.javacraft.ewrs.app.model.StoredEventRecord;
import dev.nklip.javacraft.ewrs.events.Event;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one stored event to the durable read models and checkpoint.
 * Architecture mapping: this is the {@code Coordinator -> Applier -> request/budget/checkpoint} step from the
 * Projection Flow.
 */
@Service
public class ProjectionApplier {

    private final WorkRequestProjectionRepository workRequestProjectionRepository;
    private final BudgetProjectionRepository budgetProjectionRepository;
    private final ProjectionCheckpointRepository projectionCheckpointRepository;

    public ProjectionApplier(
            WorkRequestProjectionRepository workRequestProjectionRepository,
            BudgetProjectionRepository budgetProjectionRepository,
            ProjectionCheckpointRepository projectionCheckpointRepository
    ) {
        this.workRequestProjectionRepository = workRequestProjectionRepository;
        this.budgetProjectionRepository = budgetProjectionRepository;
        this.projectionCheckpointRepository = projectionCheckpointRepository;
    }

    @Transactional
    public ProjectionUpdateResponse apply(String projectionName, StoredEventRecord storedEventRecord) {
        Event event = storedEventRecord.event();
        String requestedBy = workRequestProjectionRepository.findByRequestId(event.getTaskId())
                .map(WorkRequestResponse::requestedBy)
                .orElse(event.getActor());

        WorkRequestResponse workRequest = new WorkRequestResponse(
                event.getTaskId(),
                event.getTitle(),
                event.getPriority(),
                event.getBudgetCode(),
                event.getEstimate(),
                event.getStatus(),
                requestedBy,
                event.getActor(),
                event.getReason(),
                event.getEventId(),
                event.getOccurredAt(),
                event.getStreamVersion()
        );

        workRequestProjectionRepository.upsert(workRequest);
        budgetProjectionRepository.recalculate(event.getBudgetCode(), event.getOccurredAt());
        projectionCheckpointRepository.save(projectionName, storedEventRecord.storeId());

        BudgetProjectionResponse budget = budgetProjectionRepository.findByBudgetCode(event.getBudgetCode())
                .orElseThrow(() -> new IllegalStateException(
                        "Missing budget projection for code " + event.getBudgetCode()
                ));

        return new ProjectionUpdateResponse(
                "WORK_REQUEST_UPDATED",
                event.getEventId(),
                event.getOccurredAt(),
                workRequest,
                budget
        );
    }
}
