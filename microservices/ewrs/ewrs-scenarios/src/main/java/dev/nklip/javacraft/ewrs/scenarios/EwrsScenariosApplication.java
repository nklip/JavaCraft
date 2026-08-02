package dev.nklip.javacraft.ewrs.scenarios;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the standalone EWRS scenario driver application.
 * This module sits next to the core EWRS service and drives {@code ewrs-app} over HTTP for demos, seeded flows,
 * and reusable test scenarios without taking ownership of the write or read sides.
 */
@SpringBootApplication
public class EwrsScenariosApplication {

    static void main(String[] args) {
        SpringApplication.run(EwrsScenariosApplication.class, args);
    }
}
