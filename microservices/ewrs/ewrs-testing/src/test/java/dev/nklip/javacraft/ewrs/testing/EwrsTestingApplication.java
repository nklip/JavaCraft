package dev.nklip.javacraft.ewrs.testing;

import dev.nklip.javacraft.ewrs.app.EwrsApplication;
import dev.nklip.javacraft.ewrs.scenarios.EwrsScenariosApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composite test application that exposes both the EWRS core API and the scenario-driver API on one random port.
 * This lets end-to-end tests exercise the same scenario HTTP surface that manual Swagger users will call.
 */
@SpringBootApplication(scanBasePackageClasses = {
        EwrsApplication.class,
        EwrsScenariosApplication.class
})
public class EwrsTestingApplication {
}
