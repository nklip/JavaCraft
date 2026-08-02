package dev.nklip.javacraft.ewrs.testing.cucumber.config;

import dev.nklip.javacraft.ewrs.testing.EwrsTestingApplication;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ContextConfiguration;

@CucumberContextConfiguration
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = EwrsTestingApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.dev.nklip.javacraft.ewrs=WARN",
                "spring.application.name=ewrs-testing",
                // Jackson 3 turns EnumFeature READ/WRITE_ENUMS_USING_TO_STRING on by default;
                // EventStatus.toString() is a display label, so keep the name() wire format.
                "spring.jackson.datatype.enum.write-enums-using-to-string=false",
                "spring.jackson.datatype.enum.read-enums-using-to-string=false",
                "spring.http.clients.imperative.factory=simple",
                "ewrs.scenarios.target-base-url=",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        }
)
@ContextConfiguration(initializers = PostgresContainerInitializer.class)
@SuppressWarnings("unused")
public class CucumberSpringConfiguration {
}
