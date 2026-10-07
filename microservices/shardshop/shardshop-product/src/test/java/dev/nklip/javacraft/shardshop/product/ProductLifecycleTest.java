package dev.nklip.javacraft.shardshop.product;

import io.quarkus.runtime.Quarkus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class ProductLifecycleTest {
    @Test
    void serverWaitsForShutdownUnlessExplicitlyCheckingStartup() {
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new ProductApplication().run());
            runtime.verify(Quarkus::waitForExit);
        }
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new ProductApplication().run("--unknown"));
            runtime.verify(Quarkus::waitForExit);
        }
        assertEquals(0, new ProductApplication().run("--check-startup"));
    }
}
