package dev.nklip.javacraft.shardshop.workload.producer;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusMainTest
class OrderProducerApplicationTest {

    @Test
    @Launch({})
    void startsAndExitsWithoutExternalServices(LaunchResult result) {
        assertEquals(0, result.exitCode());
    }
}
