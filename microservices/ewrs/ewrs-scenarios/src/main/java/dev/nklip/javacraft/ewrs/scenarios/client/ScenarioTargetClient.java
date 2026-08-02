package dev.nklip.javacraft.ewrs.scenarios.client;

import dev.nklip.javacraft.ewrs.api.command.ApproveWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CompleteWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CreateWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.RejectWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.StartWorkRequest;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.api.shared.ErrorResponse;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Gateway from the scenario driver into the real EWRS HTTP API.
 * It exists so scenario orchestration stays decoupled from the concrete HTTP client implementation.
 */
public interface ScenarioTargetClient {

    WorkRequestResponse create(CreateWorkRequest request);

    WorkRequestResponse approve(int requestId, ApproveWorkRequest request);

    WorkRequestResponse reject(int requestId, RejectWorkRequest request);

    WorkRequestResponse start(int requestId, StartWorkRequest request);

    WorkRequestResponse complete(int requestId, CompleteWorkRequest request);

    ErrorResponse startExpectingConflict(int requestId, StartWorkRequest request);

    @Nullable
    WorkRequestResponse getRequestIfAvailable(int requestId);

    List<WorkRequestTimelineEventResponse> getTimeline(int requestId);

    String targetBaseUrl();
}
