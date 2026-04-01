package dev.nklip.javacraft.linker.datamanager;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableMongoRepositories
@OpenAPIDefinition(
        info = @Info(
                title = "Linker API",
                version = "1.0",
                description = "Swagger UI for short-link creation, redirect, and analytics endpoints"
        )
)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
