package dev.nklip.javacraft.shardshop.workload.producer.load;

import com.fasterxml.jackson.core.JsonParseException;
import dev.nklip.javacraft.shardshop.workload.control.ControlStatus;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    private final List<Duration> waits = new ArrayList<>();
    private final WorkloadControl control = WorkloadFixtures.control("1");
    private final RetryPolicy retry = new RetryPolicy(4, Duration.ofMillis(100), Duration.ofMillis(200),
            waits::add, () -> 0.5, control.traffic());

    @Test
    void repeatsOnlyTransientFailuresWithBoundedJitterAndStopsAtTheAttemptLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("issued-id", retry.call(OrderWorkload.ALLOCATION, "Allocate", () -> switch (calls.incrementAndGet()) {
            case 1 -> throw new IOException("lost response");
            case 2 -> throw WorkloadFixtures.httpFailure(503, "ORDER_STORE_UNAVAILABLE");
            default -> "issued-id";
        }));
        assertEquals(List.of(Duration.ofMillis(75), Duration.ofMillis(150)), waits);
        calls.set(0);
        assertThrows(ProducerException.class, () -> retry.call(OrderWorkload.ALLOCATION, "Allocate", () -> {
            calls.incrementAndGet();
            throw new IOException("lost response");
        }));
        assertEquals(4, calls.get());
        // Each attempt is one request; the repeated attempts are retries and the failed attempts are errors.
        assertEquals(new ControlStatus.TrafficCategory("allocation", 7, 5, 6), control.status().traffic().get(1));
    }

    @Test
    void rejectionsAndInvalidResponsesNeverRetryOrExposePayloads() {
        for (Exception cause : List.of(WorkloadFixtures.httpFailure(409, "ORDER_IDEMPOTENCY_CONFLICT"),
                new IllegalArgumentException("private profile"), new CharacterCodingException(),
                new JsonParseException(null, "private profile"))) {
            AtomicInteger calls = new AtomicInteger();
            ProducerException failure = assertThrows(ProducerException.class, () -> retry.call(OrderWorkload.SUBMISSION, "Submit", () -> {
                calls.incrementAndGet();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw (IllegalArgumentException) cause;
            }));
            assertEquals(1, calls.get());
            assertFalse(failure.getMessage().contains("private"));
        }
        assertTrue(waits.isEmpty());
    }

    @Test
    void interruptionIsNotRetried() {
        assertThrows(InterruptedException.class, () -> retry.call(OrderWorkload.POLLING, "Poll", () -> {
            throw new InterruptedException();
        }));
        assertTrue(waits.isEmpty());
    }
}
