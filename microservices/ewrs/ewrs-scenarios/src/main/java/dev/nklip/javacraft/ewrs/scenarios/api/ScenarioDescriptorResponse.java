package dev.nklip.javacraft.ewrs.scenarios.api;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import java.util.List;

/**
 * Describes one scenario template exposed by the scenario driver.
 * It exists so callers can discover what each scenario produces before generating data or load.
 */
public record ScenarioDescriptorResponse(
        ScenarioType scenario,
        String description,
        List<EventStatus> expectedTerminalStatuses,
        int defaultCount,
        boolean supportsLoad
) {
}
