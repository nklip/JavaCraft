package dev.nklip.javacraft.elastic.data.cucumber.conf;

import dev.nklip.javacraft.elastic.app.ElasticApplication;
import io.cucumber.spring.CucumberContextConfiguration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ContextConfiguration;

/**
 * Bridges Cucumber and Spring Boot so scenario steps can run against the test application context.
 * The class has no direct Java references because Cucumber discovers it reflectively via
 * {@link CucumberContextConfiguration}, which is why IDEs may report it as unused.
 */
@CucumberContextConfiguration
@SpringBootTest(
        classes = ElasticApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT
)
@ContextConfiguration(initializers = ElasticsearchContainerInitializer.class)
@Slf4j
public class CucumberSpringConfiguration {

    private static final int POLL_INTERVAL_MILLIS = 200;
    private static final int MAX_WAIT_MILLIS = 2000;

    // ES is eventually consistent so there is a delay in updating & retrieving updated data
    public static <T> boolean assertWithWait(T expected, Supplier<T> supplier) throws InterruptedException {
        boolean confirmed = false;

        int elapsedMillis = 0;
        while (elapsedMillis < MAX_WAIT_MILLIS) {

            if (expected.equals(supplier.get())) {
                confirmed = true;
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);

            elapsedMillis += POLL_INTERVAL_MILLIS;
        }
        if (!confirmed) {
            log.info("Size = {}", supplier.get());
        }
        return confirmed;
    }

}
