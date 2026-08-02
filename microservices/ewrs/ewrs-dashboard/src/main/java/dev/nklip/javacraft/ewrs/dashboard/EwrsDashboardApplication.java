package dev.nklip.javacraft.ewrs.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the standalone EWRS dashboard application.
 * Architecture mapping: this is the browser-facing read-only visualizer described in the EWRS Read Side that renders
 * charts from SQL projections and event history without participating in command handling.
 */
@SpringBootApplication
public class EwrsDashboardApplication {

    static void main(String[] args) {
        SpringApplication.run(EwrsDashboardApplication.class, args);
    }
}
