package dev.nklip.javacraft.soap2rest.rest.app.cucumber.conf;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

public class ArtemisContainerInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String ARTEMIS_USER = "artemis";
    private static final String ARTEMIS_PASSWORD = "artemis";
    private static final int ARTEMIS_CORE_PORT = 61616;

    private static final GenericContainer<?> CONTAINER = createContainer();

    static {
        CONTAINER.start();
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> createContainer() {
        String image = System.getProperty("tc.image.artemis");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "System property 'tc.image.artemis' is not set. "
                    + "It must be provided via Maven Surefire systemPropertyVariables in pom.xml.");
        }
        // The container stays up for the full cucumber JVM and Ryuk cleans it up afterwards.
        return new GenericContainer<>(DockerImageName.parse(image))
                .withEnv("ARTEMIS_USER", ARTEMIS_USER)
                .withEnv("ARTEMIS_PASSWORD", ARTEMIS_PASSWORD)
                .waitingFor(Wait.forLogMessage(".*AMQ221007: Server is now active.*", 1))
                .withStartupTimeout(Duration.ofSeconds(60))
                .withExposedPorts(ARTEMIS_CORE_PORT);
    }

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        String brokerUrl = "tcp://%s:%s".formatted(
                CONTAINER.getHost(),
                CONTAINER.getMappedPort(ARTEMIS_CORE_PORT)
        );

        TestPropertyValues.of(
                "spring.artemis.mode=native",
                "spring.artemis.embedded.enabled=false",
                "spring.artemis.broker-url=" + brokerUrl,
                "spring.artemis.host=" + CONTAINER.getHost(),
                "spring.artemis.port=" + CONTAINER.getMappedPort(ARTEMIS_CORE_PORT),
                "spring.artemis.user=" + ARTEMIS_USER,
                "spring.artemis.password=" + ARTEMIS_PASSWORD
        ).applyTo(ctx.getEnvironment());
    }
}
