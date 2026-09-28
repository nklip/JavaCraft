package dev.nklip.javacraft.shardshop.workload.reader;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProductReaderApplicationTest {

    private final ConfigurableApplicationContext context;

    @Autowired
    ProductReaderApplicationTest(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Test
    void mainStartsApplicationWithoutExternalServices() {
        assertTrue(context.isActive());
        assertNotNull(context.getBean(ProductReaderApplication.class));
    }
}
