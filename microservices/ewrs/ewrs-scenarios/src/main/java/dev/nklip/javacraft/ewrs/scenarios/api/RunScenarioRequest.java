package dev.nklip.javacraft.ewrs.scenarios.api;

import jakarta.validation.constraints.Min;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * Optional knobs for scenario execution and repeatable load generation.
 * This exists so Swagger users and automated tests can drive deterministic EWRS flows without changing code.
 */
public record RunScenarioRequest(
        @Min(1) Integer count,
        String titlePrefix,
        String requestedBy
) {

    public static RunScenarioRequest defaults() {
        return new RunScenarioRequest(1, null, null);
    }

    public int resolvedCount() {
        return count == null ? 1 : count;
    }

    public String resolvedTitlePrefix(ScenarioType scenarioType) {
        return StringUtils.hasText(titlePrefix)
                ? titlePrefix.trim()
                : scenarioType.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public String resolvedRequestedBy() {
        return StringUtils.hasText(requestedBy) ? requestedBy.trim() : "ScenarioDriver";
    }
}
