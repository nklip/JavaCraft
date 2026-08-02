package dev.nklip.javacraft.ewrs.testing.cucumber.config;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("deprecation")
public class PostgresContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String TC_POSTGRESQL_IMAGE = "tc.image.postgresql";

    private static final PostgreSQLContainer<?> CONTAINER = createContainer();

    static {
        CONTAINER.start();
    }

    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> createContainer() {
        String dockerImage = MavenPomPropertyResolver.resolveRequiredSystemOrPomProperty(TC_POSTGRESQL_IMAGE);
        return new PostgreSQLContainer<>(DockerImageName.parse(dockerImage))
                .withDatabaseName("ewrs")
                .withUsername("ewrs")
                .withPassword("ewrs");
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
