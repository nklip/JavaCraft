package my.javacraft.elastic.data.cucumber.conf;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a singleton Elasticsearch container before the Spring application context
 * is created and registers the container's host/port into the environment so that
 * {@code ElasticsearchConfiguration} picks them up via its
 * {@code spring.elastic.cluster.*} properties.
 *
 * <p>The container is started exactly once per JVM via the static initialiser.
 * Testcontainers' Ryuk sidecar ensures it is stopped when the JVM exits.
 */
public class ElasticsearchContainerInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String ELASTIC_PASSWORD = "test_elastic";

    private static final ElasticsearchContainer CONTAINER = createContainer();

    static {
        CONTAINER.start();
    }

    @SuppressWarnings("resource")
    private static ElasticsearchContainer createContainer() {
        String image = System.getProperty("tc.image.elasticsearch");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "System property 'tc.image.elasticsearch' is not set. "
                    + "It must be provided via Maven Surefire systemPropertyVariables in pom.xml.");
        }
        // The container stays up for the full cucumber JVM and Ryuk cleans it up afterwards.
        return new ElasticsearchContainer(DockerImageName.parse(image))
                .withEnv("xpack.security.http.ssl.enabled", "false")
                .withEnv("ELASTIC_PASSWORD", ELASTIC_PASSWORD);
    }

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        TestPropertyValues.of(
                "spring.elastic.cluster.host=" + CONTAINER.getHost(),
                "spring.elastic.cluster.port=" + CONTAINER.getMappedPort(9200),
                "spring.elastic.cluster.ssl.enabled=false",
                "spring.elastic.cluster.user=elastic",
                "spring.elastic.cluster.pass=" + ELASTIC_PASSWORD
        ).applyTo(ctx.getEnvironment());
    }
}
