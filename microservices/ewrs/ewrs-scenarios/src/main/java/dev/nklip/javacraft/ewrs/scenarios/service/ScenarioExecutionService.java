package dev.nklip.javacraft.ewrs.scenarios.service;

import dev.nklip.javacraft.ewrs.api.command.ApproveWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CompleteWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CreateWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.RejectWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.StartWorkRequest;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.RunScenarioRequest;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioExecutionResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioRunResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioType;
import dev.nklip.javacraft.ewrs.scenarios.client.ScenarioTargetClient;
import dev.nklip.javacraft.ewrs.scenarios.config.ScenariosProperties;
import dev.nklip.javacraft.ewrs.scenarios.exception.ScenarioExecutionException;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Orchestrates deterministic EWRS workflows by calling the real application over HTTP.
 * This keeps scenario generation reusable for demos and tests while respecting the boundaries of the core app.
 */
@Service
public class ScenarioExecutionService {

    private final ScenarioTargetClient scenarioTargetClient;
    private final ScenariosProperties properties;
    private final Clock clock;

    public ScenarioExecutionService(
            ScenarioTargetClient scenarioTargetClient,
            ScenariosProperties properties,
            @Qualifier("scenarioClock")
            Clock scenarioClock
    ) {
        this.scenarioTargetClient = scenarioTargetClient;
        this.properties = properties;
        this.clock = scenarioClock;
    }

    public ScenarioExecutionResponse runScenario(ScenarioType scenarioType, RunScenarioRequest rawRequest) {
        RunScenarioRequest request = rawRequest == null ? RunScenarioRequest.defaults() : rawRequest;
        List<ScenarioRunResponse> runs = new ArrayList<>();

        for (int iteration = 1; iteration <= request.resolvedCount(); iteration++) {
            runs.add(executeScenarioRun(scenarioType, iteration, request));
        }

        return new ScenarioExecutionResponse(
                scenarioType,
                request.resolvedCount(),
                Instant.now(clock),
                scenarioTargetClient.targetBaseUrl(),
                List.copyOf(runs)
        );
    }

    private ScenarioRunResponse executeScenarioRun(
            ScenarioType scenarioType,
            int iteration,
            RunScenarioRequest request
    ) {
        ScenarioType variant = scenarioType == ScenarioType.MIXED_LOAD ? mixedLoadVariant(iteration) : scenarioType;

        return switch (variant) {
            case CREATE_ONLY -> executeCreateOnly(iteration, request);
            case HAPPY_PATH -> executeHappyPath(iteration, request);
            case APPROVAL_DENIED -> executeApprovalDenied(iteration, request);
            case EXPLICIT_REJECT -> executeExplicitReject(iteration, request);
            case INVALID_START -> executeInvalidStart(iteration, request);
            case MIXED_LOAD -> throw new IllegalStateException("Mixed load must resolve to a concrete variant first");
        };
    }

    private ScenarioRunResponse executeCreateOnly(int iteration, RunScenarioRequest request) {
        WorkRequestResponse created = scenarioTargetClient.create(new CreateWorkRequest(
                titleFor(request, ScenarioType.CREATE_ONLY, iteration),
                Priority.NORMAL,
                "PLATFORM-2026",
                10,
                request.resolvedRequestedBy(),
                correlationId(ScenarioType.CREATE_ONLY, iteration, "create")
        ));

        return awaitScenarioState(
                iteration,
                ScenarioType.CREATE_ONLY,
                created.requestId(),
                List.of(EventStatus.CREATED),
                false
        );
    }

    private ScenarioRunResponse executeHappyPath(int iteration, RunScenarioRequest request) {
        WorkRequestResponse created = scenarioTargetClient.create(new CreateWorkRequest(
                titleFor(request, ScenarioType.HAPPY_PATH, iteration),
                Priority.CRITICAL,
                "PLATFORM-2026",
                40,
                request.resolvedRequestedBy(),
                correlationId(ScenarioType.HAPPY_PATH, iteration, "create")
        ));

        scenarioTargetClient.approve(created.requestId(), new ApproveWorkRequest(
                "Lead",
                correlationId(ScenarioType.HAPPY_PATH, iteration, "approve")
        ));
        scenarioTargetClient.start(created.requestId(), new StartWorkRequest(
                "Worker",
                correlationId(ScenarioType.HAPPY_PATH, iteration, "start")
        ));
        scenarioTargetClient.complete(created.requestId(), new CompleteWorkRequest(
                "Worker",
                correlationId(ScenarioType.HAPPY_PATH, iteration, "complete")
        ));

        return awaitScenarioState(
                iteration,
                ScenarioType.HAPPY_PATH,
                created.requestId(),
                List.of(EventStatus.CREATED, EventStatus.ACCEPTED, EventStatus.RUNNING, EventStatus.COMPLETED),
                false
        );
    }

    private ScenarioRunResponse executeApprovalDenied(int iteration, RunScenarioRequest request) {
        WorkRequestResponse created = scenarioTargetClient.create(new CreateWorkRequest(
                titleFor(request, ScenarioType.APPROVAL_DENIED, iteration),
                Priority.MAJOR,
                "OPS-2026",
                130,
                request.resolvedRequestedBy(),
                correlationId(ScenarioType.APPROVAL_DENIED, iteration, "create")
        ));

        scenarioTargetClient.approve(created.requestId(), new ApproveWorkRequest(
                "Lead",
                correlationId(ScenarioType.APPROVAL_DENIED, iteration, "approve")
        ));

        return awaitScenarioState(
                iteration,
                ScenarioType.APPROVAL_DENIED,
                created.requestId(),
                List.of(EventStatus.CREATED, EventStatus.REJECTED),
                false
        );
    }

    private ScenarioRunResponse executeExplicitReject(int iteration, RunScenarioRequest request) {
        WorkRequestResponse created = scenarioTargetClient.create(new CreateWorkRequest(
                titleFor(request, ScenarioType.EXPLICIT_REJECT, iteration),
                Priority.NORMAL,
                "RND-2026",
                20,
                request.resolvedRequestedBy(),
                correlationId(ScenarioType.EXPLICIT_REJECT, iteration, "create")
        ));

        scenarioTargetClient.reject(created.requestId(), new RejectWorkRequest(
                "Operator",
                "Missing sign-off",
                correlationId(ScenarioType.EXPLICIT_REJECT, iteration, "reject")
        ));

        return awaitScenarioState(
                iteration,
                ScenarioType.EXPLICIT_REJECT,
                created.requestId(),
                List.of(EventStatus.CREATED, EventStatus.REJECTED),
                false
        );
    }

    private ScenarioRunResponse executeInvalidStart(int iteration, RunScenarioRequest request) {
        WorkRequestResponse created = scenarioTargetClient.create(new CreateWorkRequest(
                titleFor(request, ScenarioType.INVALID_START, iteration),
                Priority.MINOR,
                "OPS-2026",
                10,
                request.resolvedRequestedBy(),
                correlationId(ScenarioType.INVALID_START, iteration, "create")
        ));

        scenarioTargetClient.startExpectingConflict(created.requestId(), new StartWorkRequest(
                "Worker",
                correlationId(ScenarioType.INVALID_START, iteration, "start")
        ));

        return awaitScenarioState(
                iteration,
                ScenarioType.INVALID_START,
                created.requestId(),
                List.of(EventStatus.CREATED),
                true
        );
    }

    private ScenarioRunResponse awaitScenarioState(
            int iteration,
            ScenarioType variant,
            int requestId,
            List<EventStatus> expectedTimeline,
            boolean expectedConflictObserved
    ) {
        long deadline = System.nanoTime() + properties.projectionTimeout().toNanos();
        EventStatus expectedStatus = expectedTimeline.getLast();

        while (System.nanoTime() <= deadline) {
            WorkRequestResponse projected = scenarioTargetClient.getRequestIfAvailable(requestId);
            List<EventStatus> timelineStatuses = scenarioTargetClient.getTimeline(requestId).stream()
                    .map(WorkRequestTimelineEventResponse::status)
                    .toList();

            if (projected != null
                    && projected.status() == expectedStatus
                    && timelineStatuses.equals(expectedTimeline)) {
                return new ScenarioRunResponse(
                        iteration,
                        variant,
                        requestId,
                        expectedStatus,
                        projected.status(),
                        timelineStatuses,
                        expectedConflictObserved
                );
            }

            sleepBeforeRetry();
        }

        throw new ScenarioExecutionException("""
                Timed out waiting for scenario %s request %s to project %s with timeline %s
                """.stripIndent().formatted(variant, requestId, expectedStatus, expectedTimeline));
    }

    private ScenarioType mixedLoadVariant(int iteration) {
        return switch ((iteration - 1) % 4) {
            case 0 -> ScenarioType.HAPPY_PATH;
            case 1 -> ScenarioType.APPROVAL_DENIED;
            case 2 -> ScenarioType.EXPLICIT_REJECT;
            default -> ScenarioType.INVALID_START;
        };
    }

    private String titleFor(RunScenarioRequest request, ScenarioType scenarioType, int iteration) {
        return "%s %s".formatted(request.resolvedTitlePrefix(scenarioType), iteration);
    }

    private String correlationId(ScenarioType scenarioType, int iteration, String action) {
        return "scenario-%s-%s-%s".formatted(
                scenarioType.name().toLowerCase(Locale.ROOT),
                iteration,
                action
        );
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(properties.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScenarioExecutionException("Interrupted while waiting for EWRS projections", e);
        }
    }
}
