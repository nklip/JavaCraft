package dev.nklip.javacraft.ewrs.scenarios.service;

import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioDescriptorResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioType;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Publishes the static catalog of available scenario templates.
 * This gives Swagger and automated callers a discoverable view of what the scenario driver can generate.
 */
@Service
public class ScenarioCatalogService {

    public List<ScenarioDescriptorResponse> list() {
        return Arrays.stream(ScenarioType.values())
                .map(scenario -> new ScenarioDescriptorResponse(
                        scenario,
                        scenario.description(),
                        scenario.expectedTerminalStatuses(),
                        1,
                        true
                ))
                .toList();
    }
}
