package dev.nklip.javacraft.openflights.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Slf4j
@EnableJpaRepositories("dev.nklip.javacraft.openflights.jpa.repository")
@EntityScan("dev.nklip.javacraft.openflights.jpa.entity")
@SpringBootApplication(scanBasePackages = "dev.nklip.javacraft.openflights")
public class OpenFlightsApplication {

    static void main(String[] args) {
        log.info("OpenFlights application starting...");
        SpringApplication.run(OpenFlightsApplication.class, args);
    }
}
