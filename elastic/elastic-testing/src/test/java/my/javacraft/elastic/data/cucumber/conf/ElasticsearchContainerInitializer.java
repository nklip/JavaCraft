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

    private static final String ELASTICSEARCH_IMAGE =
            "docker.elastic.co/elasticsearch/elasticsearch:8.18.8";

    static final String ELASTIC_PASSWORD = "test_elastic";

    private static final ElasticsearchContainer CONTAINER =
            new ElasticsearchContainer(DockerImageName.parse(ELASTICSEARCH_IMAGE))
                    .withEnv("xpack.security.http.ssl.enabled", "false")
                    .withEnv("ELASTIC_PASSWORD", ELASTIC_PASSWORD);

    static {
        CONTAINER.start();
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
