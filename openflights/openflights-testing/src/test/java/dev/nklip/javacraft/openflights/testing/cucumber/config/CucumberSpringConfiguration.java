package dev.nklip.javacraft.openflights.testing.cucumber.config;

import dev.nklip.javacraft.openflights.kafka.consumer.KafkaConsumerApplication;
import io.cucumber.spring.CucumberContextConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ContextConfiguration;

/**
 * Boots the OpenFlights test application so Cucumber steps can hit the producer HTTP endpoints
 * while Kafka listeners persist the same records into PostgreSQL.
 *
 * The test intentionally uses the same Kafka route settings as the normal runtime configuration.
 * The reported ingestion timings are therefore observational only and can vary by machine,
 * available CPU, Docker load, and local PostgreSQL/Kafka performance.
 */
@SuppressWarnings("unused")
@CucumberContextConfiguration
@SpringBootTest(
        classes = KafkaConsumerApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "logging.level.org.apache.kafka=WARN"
        }
)
@ContextConfiguration(initializers = {
        KafkaContainerInitializer.class,
        PostgresContainerInitializer.class
})
@Slf4j
public class CucumberSpringConfiguration {
}
