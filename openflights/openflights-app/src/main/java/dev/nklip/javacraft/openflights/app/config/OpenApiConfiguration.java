package dev.nklip.javacraft.openflights.app.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfiguration {

    @Bean
    OpenAPI openFlightsOpenApi() {
        return new OpenAPI()
                .externalDocs(new ExternalDocumentation()
                        .description("OpenFlights SQL Console")
                        .url("/index.html"));
    }
}
