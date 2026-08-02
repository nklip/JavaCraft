package dev.nklip.javacraft.ewrs.scenarios.api;

import java.time.Instant;
import java.util.List;

/**
 * Summary returned after a scenario or load batch has been executed against {@code ewrs-app}.
 * It exists so callers can see which request ids were generated and what final projected state each run reached.
 */
public record ScenarioExecutionResponse(
        ScenarioType scenario,
        int requestedCount,
        Instant executedAt,
        String targetBaseUrl,
        List<ScenarioRunResponse> runs
) {
}
