package dev.nklip.javacraft.bdd.cucumber.conf;

import dev.nklip.javacraft.bdd.Application;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Bridges Cucumber and Spring Boot so scenario steps can run against the test application context.
 * The class has no direct Java references because Cucumber discovers it reflectively via
 * {@link CucumberContextConfiguration}, which is why IDEs may report it as unused.
 */
@SuppressWarnings("unused")
@CucumberContextConfiguration
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class CucumberSpringConfiguration {
}
