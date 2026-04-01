package dev.nklip.javacraft.elastic.data.cucumber.conf;

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

    static final String TC_ELASTICSEARCH_IMAGE = "tc.image.elasticsearch";
    static final String ELASTIC_PASSWORD = "test_elastic";

    private static final ElasticsearchContainer CONTAINER = createContainer();

    static {
        CONTAINER.start();
    }

    @SuppressWarnings("resource")
    private static ElasticsearchContainer createContainer() {
        String dockerImage = MavenPomPropertyResolver.resolveRequiredSystemOrPomProperty(TC_ELASTICSEARCH_IMAGE);
        // The container stays up for the full cucumber JVM and Ryuk cleans it up afterwards.
        return new ElasticsearchContainer(DockerImageName.parse(dockerImage))
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
