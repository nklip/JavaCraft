package dev.nklip.javacraft.ewrs.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the EWRS runtime described in {@code ARCHITECTURE.md}.
 * Architecture mapping: startup entry point that wires the Runtime Topology so controllers, write-side services,
 * repositories, projector components, and SSE publishing can start participating in the flow.
 */
@SpringBootApplication(scanBasePackages = "dev.nklip.javacraft.ewrs")
public class EwrsApplication {

    static void main(String[] args) {
        SpringApplication.run(EwrsApplication.class, args);
    }
}
