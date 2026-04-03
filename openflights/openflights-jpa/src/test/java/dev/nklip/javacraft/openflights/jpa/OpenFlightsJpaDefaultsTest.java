package dev.nklip.javacraft.openflights.jpa;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class OpenFlightsJpaDefaultsTest {

    @Test
    void sharedJpaDefaultsExposeLocalPostgresConfiguration() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load(
                "openflights-jpa-defaults",
                new ClassPathResource("openflights-jpa-defaults.yaml"));

        Assertions.assertEquals(1, propertySources.size());
        PropertySource<?> propertySource = propertySources.getFirst();
        Assertions.assertEquals("jdbc:postgresql://localhost:5432/openflights",
                propertySource.getProperty("spring.datasource.url"));
        Assertions.assertEquals("openflights", propertySource.getProperty("spring.datasource.username"));
        Assertions.assertEquals("openflights", propertySource.getProperty("spring.datasource.password"));
        Assertions.assertEquals("update", propertySource.getProperty("spring.jpa.hibernate.ddl-auto"));
    }
}
