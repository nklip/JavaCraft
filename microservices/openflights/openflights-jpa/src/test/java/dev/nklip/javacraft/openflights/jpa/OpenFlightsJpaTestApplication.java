package dev.nklip.javacraft.openflights.jpa;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableJpaRepositories("dev.nklip.javacraft.openflights.jpa.repository")
@EntityScan("dev.nklip.javacraft.openflights.jpa.entity")
@SpringBootApplication(scanBasePackages = "dev.nklip.javacraft.openflights.jpa")
public class OpenFlightsJpaTestApplication {
}
