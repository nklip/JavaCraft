package dev.nklip.javacraft.elastic.app;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.elastic.app.config.SearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(SearchProperties.class)
@OpenAPIDefinition(
        info = @Info(
                title = "Elastic application",
                version = "1.0",
                description = "Swagger UI for Elastic application"
        )
)
public class ElasticApplication {

    public static void main(String[] args){
        log.info("Elastic ElasticApplication starting...");
        SpringApplication.run(ElasticApplication.class, args);
    }

}
