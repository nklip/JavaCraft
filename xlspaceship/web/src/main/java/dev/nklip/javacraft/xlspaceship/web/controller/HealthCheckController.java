package dev.nklip.javacraft.xlspaceship.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health")
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthCheckController {

    private final HealthEndpoint healthEndpoint;

    @Operation(
            summary = "Standard health check",
            description = "This API could be used to check the status of the game."
    )
    @ApiResponse(responseCode = "200", description = "Success")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthComponent health() {
        return healthEndpoint.health();
    }
}
