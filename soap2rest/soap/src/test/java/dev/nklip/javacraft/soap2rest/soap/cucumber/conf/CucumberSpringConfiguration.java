package dev.nklip.javacraft.soap2rest.soap.cucumber.conf;

import io.cucumber.spring.CucumberContextConfiguration;
import dev.nklip.javacraft.soap2rest.soap.SoapApplication;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Bridges Cucumber and Spring Boot so scenario steps can run against the test application context.
 * The class has no direct Java references because Cucumber discovers it reflectively via
 * {@link CucumberContextConfiguration}, which is why IDEs may report it as unused.
 */
@SuppressWarnings("unused")
@CucumberContextConfiguration
@SpringBootTest(
        classes = SoapApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public class CucumberSpringConfiguration {
}
