package dev.nklip.javacraft.shardshop.workload.reader.config;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusMainTest
@TestProfile(ReaderWiringTest.ShortDeadline.class)
class ReaderWiringTest {

    @Test
    @Launch(value = "--check-startup", exitCode = 1)
    void rejectsADeadlineThatEndsBeforeProductCanReportUnavailability(LaunchResult result) {
        String output = result.getOutput() + result.getErrorOutput();
        assertTrue(output.contains("shardshop.reader.request-deadline must be at least 5s"), output);
    }

    public static class ShortDeadline implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("shardshop.reader.request-deadline", "4999ms");
        }
    }
}
