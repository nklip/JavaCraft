package dev.nklip.javacraft.ewrs.scenarios.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the OpenAPI metadata for the standalone scenario driver.
 * This makes the demo and load-generation endpoints discoverable in Swagger without mixing them into {@code ewrs-app}.
 */
@Configuration("ewrsScenariosOpenApiConfiguration")
public class OpenApiConfiguration {

    @Bean
    public OpenAPI sesScenariosOpenApi() {
        return new OpenAPI().info(new Info()
                .title("EWRS Scenario Driver")
                .description("Standalone scenario and deterministic load generator for the EWRS.")
                .version("v1"));
    }
}
