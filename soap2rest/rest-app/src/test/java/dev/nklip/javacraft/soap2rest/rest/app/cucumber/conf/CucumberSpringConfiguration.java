package dev.nklip.javacraft.soap2rest.rest.app.cucumber.conf;

import io.cucumber.spring.CucumberContextConfiguration;
import dev.nklip.javacraft.soap2rest.rest.app.Application;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * Bridges Cucumber and Spring Boot so scenario steps can run against the test application context.
 * The class has no direct Java references because Cucumber discovers it reflectively via
 * {@link CucumberContextConfiguration}, which is why IDEs may report it as unused.
 */
@SuppressWarnings("unused")
@CucumberContextConfiguration
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ContextConfiguration(initializers = ArtemisContainerInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class CucumberSpringConfiguration {
}
