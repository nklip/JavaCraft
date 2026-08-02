package dev.nklip.javacraft.ewrs.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the OpenAPI metadata for the EWRS HTTP surface.
 * Architecture mapping: startup-time support component that documents the controller entry points from the
 * Runtime Topology and Read Side sections.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI ewrsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("EWRS Work Request Application")
                .description("Event-sourced work request application with CQRS projections, replay, and SSE updates.")
                .version("v1"));
    }
}
