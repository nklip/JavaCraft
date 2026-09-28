package dev.nklip.javacraft.shardshop.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProductApplicationTest {

    private final ConfigurableApplicationContext context;

    @Autowired
    ProductApplicationTest(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Test
    void mainStartsApplicationWithoutExternalServices() {
        assertTrue(context.isActive());
        assertNotNull(context.getBean(ProductApplication.class));
    }
}
