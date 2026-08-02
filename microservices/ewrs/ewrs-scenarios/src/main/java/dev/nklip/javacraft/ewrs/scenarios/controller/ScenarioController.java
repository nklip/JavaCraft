package dev.nklip.javacraft.ewrs.scenarios.controller;

import dev.nklip.javacraft.ewrs.scenarios.api.RunScenarioRequest;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioDescriptorResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioExecutionResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioType;
import dev.nklip.javacraft.ewrs.scenarios.service.ScenarioCatalogService;
import dev.nklip.javacraft.ewrs.scenarios.service.ScenarioExecutionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for deterministic scenario generation and lightweight load creation.
 * It fronts the reusable scenario driver so both Swagger users and black-box tests can trigger named EWRS flows.
 */
@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    private final ScenarioCatalogService scenarioCatalogService;
    private final ScenarioExecutionService scenarioExecutionService;

    public ScenarioController(
            ScenarioCatalogService scenarioCatalogService,
            ScenarioExecutionService scenarioExecutionService
    ) {
        this.scenarioCatalogService = scenarioCatalogService;
        this.scenarioExecutionService = scenarioExecutionService;
    }

    @GetMapping
    public ResponseEntity<List<ScenarioDescriptorResponse>> listScenarios() {
        return ResponseEntity.ok(scenarioCatalogService.list());
    }

    @PostMapping("/{scenario}/run")
    public ResponseEntity<ScenarioExecutionResponse> runScenario(
            @PathVariable("scenario") ScenarioType scenarioType,
            @Valid @RequestBody(required = false) RunScenarioRequest request
    ) {
        return ResponseEntity.ok(scenarioExecutionService.runScenario(scenarioType, request));
    }

    @PostMapping("/load")
    public ResponseEntity<ScenarioExecutionResponse> generateLoad(
            @Valid @RequestBody(required = false) RunScenarioRequest request
    ) {
        return ResponseEntity.ok(scenarioExecutionService.runScenario(ScenarioType.MIXED_LOAD, request));
    }
}
