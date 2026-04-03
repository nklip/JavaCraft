package dev.nklip.javacraft.openflights.testing.cucumber.config;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a Kafka KRaft container using the same image as {@code compose.yaml},
 * but on an isolated host port so the Cucumber test can control its own broker lifecycle.
 */
public class KafkaContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String TC_KAFKA_IMAGE = "tc.image.kafka";
    static final int CONTAINER_KAFKA_PORT = 9092;
    static final int TEST_KAFKA_PORT = 19092;

    private static final GenericContainer<?> CONTAINER = createContainer();

    static {
        CONTAINER.start();
    }

    private static GenericContainer<?> createContainer() {
        String dockerImage = MavenPomPropertyResolver.resolveRequiredSystemOrPomProperty(TC_KAFKA_IMAGE);
        return new GenericContainer<>(DockerImageName.parse(dockerImage))
                .withCreateContainerCmdModifier(command -> command.withHostConfig(
                        command.getHostConfig().withPortBindings(
                                new PortBinding(
                                        Ports.Binding.bindPort(TEST_KAFKA_PORT),
                                        new ExposedPort(CONTAINER_KAFKA_PORT)
                                )
                        )
                ))
                .withEnv("KAFKA_NODE_ID", "1")
                .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + TEST_KAFKA_PORT)
                .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
                .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                .waitingFor(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1));
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        TestPropertyValues.of(
                "spring.kafka.bootstrap-servers=localhost:" + TEST_KAFKA_PORT
        ).applyTo(applicationContext.getEnvironment());
    }
}
