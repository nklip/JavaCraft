package dev.nklip.javacraft.shardshop.workload.reader;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusMainTest
class ProductReaderApplicationTest {

    @Test
    @Launch({"--check-startup"})
    void validatesConfigurationAndExitsWithoutExternalServices(LaunchResult result) {
        assertEquals(0, result.exitCode());
    }
}
