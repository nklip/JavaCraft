package dev.nklip.javacraft.ewrs.scenarios.api;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import java.util.List;

/**
 * Outcome of a single generated work-request flow inside a scenario batch.
 * It exists so tests and manual users can correlate a generated request id with its variant, final state, and history.
 */
public record ScenarioRunResponse(
        int iteration,
        ScenarioType variant,
        int requestId,
        EventStatus expectedStatus,
        EventStatus projectedStatus,
        List<EventStatus> timelineStatuses,
        boolean expectedConflictObserved
) {
}
