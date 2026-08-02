package dev.nklip.javacraft.ewrs.dashboard.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes concise OpenAPI metadata for the dashboard's JSON endpoints.
 * Architecture mapping: documents the read-only dashboard API that sits beside the Thymeleaf page and reads EWRS
 * projections without invoking the write side.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI sesDashboardOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EWRS Dashboard API")
                        .version("v1")
                        .description("Read-only chart and timeline endpoints for the EWRS Thymeleaf dashboard.")
                )
                .externalDocs(new ExternalDocumentation()
                        .description("Dashboard UI")
                        .url("/")
                );
    }
}
