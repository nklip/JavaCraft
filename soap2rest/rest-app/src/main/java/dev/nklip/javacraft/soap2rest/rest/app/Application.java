package dev.nklip.javacraft.soap2rest.rest.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Slf4j
@EnableJpaRepositories(
        "dev.nklip.javacraft.soap2rest.rest.app.dao"
)
@EntityScan(
        "dev.nklip.javacraft.soap2rest.rest.app.dao.entity"
)
@SpringBootApplication(scanBasePackages = {
        "dev.nklip.javacraft.soap2rest.utils",
        "dev.nklip.javacraft.soap2rest.rest.app",
})
public class Application {

    public static void main(String[] args) {
        log.info("REST Application starting...");
        SpringApplication.run(Application.class, args);
    }
}
