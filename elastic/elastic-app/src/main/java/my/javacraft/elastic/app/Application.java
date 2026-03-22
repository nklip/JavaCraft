package my.javacraft.elastic.app;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
@OpenAPIDefinition(
        info = @Info(
                title = "Elastic application",
                version = "1.0",
                description = "Swagger UI for Elastic application"
        )
)
public class Application {

    public static void main(String[] args){
        log.info("Elastic Application starting...");
        SpringApplication.run(Application.class, args);
    }

}
