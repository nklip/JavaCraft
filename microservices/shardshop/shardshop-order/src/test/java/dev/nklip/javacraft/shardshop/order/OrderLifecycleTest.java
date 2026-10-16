package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.Quarkus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class OrderLifecycleTest {
    @Test
    void serverWaitsForShutdownUnlessExplicitlyCheckingStartup() {
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication().run());
            runtime.verify(Quarkus::waitForExit);
        }
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication().run("--unknown"));
            runtime.verify(Quarkus::waitForExit);
        }
        assertEquals(0, new OrderApplication().run("--check-startup"));
    }
}
