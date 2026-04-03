package dev.nklip.javacraft.openflights.testing.cucumber.config;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a singleton PostgreSQL test container using the same image and credentials as compose.yaml.
 */
public class PostgresContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String TC_POSTGRESQL_IMAGE = "tc.image.postgresql";
    static final String DATABASE_NAME = "openflights";
    static final String USERNAME = "openflights";
    static final String PASSWORD = "openflights";

    private static final PostgreSQLContainer<?> CONTAINER = createContainer();

    static {
        CONTAINER.start();
    }

    private static PostgreSQLContainer<?> createContainer() {
        String dockerImage = MavenPomPropertyResolver.resolveRequiredSystemOrPomProperty(TC_POSTGRESQL_IMAGE);
        return new PostgreSQLContainer<>(DockerImageName.parse(dockerImage))
                .withDatabaseName(DATABASE_NAME)
                .withUsername(USERNAME)
                .withPassword(PASSWORD);
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        TestPropertyValues.of(
                "spring.datasource.url=" + CONTAINER.getJdbcUrl(),
                "spring.datasource.username=" + CONTAINER.getUsername(),
                "spring.datasource.password=" + CONTAINER.getPassword(),
                "spring.datasource.driver-class-name=" + CONTAINER.getDriverClassName()
        ).applyTo(applicationContext.getEnvironment());
    }
}
