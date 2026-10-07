package dev.nklip.javacraft.shardshop.workload.seeder;

import dev.nklip.javacraft.shardshop.common.HttpResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetryPolicyTest {
    private final RetryPolicy.Pause pause = mock();
    private final RetryPolicy.Request<String> request = mock();
    private final RetryPolicy retry = new RetryPolicy(5, Duration.ofMillis(500), Duration.ofMillis(1500), pause);

    @Test
    void returnsTheFirstResponseWithoutPausing() throws Exception {
        when(request.send()).thenReturn("first");
        assertEquals("first", retry.call("Create seller key", request));
        verify(request).send();
        verifyNoInteractions(pause);
    }

    @Test
    void retriesUnavailableAndTransportFailuresWithCappedExponentialBackoff() throws Exception {
        HttpResponseException unavailable = status(503, "CATALOG_UNAVAILABLE");
        HttpResponseException uncoded = status(503, null);
        when(request.send()).thenThrow(unavailable, new HttpTimeoutException("deadline"),
                new ConnectException("refused"), uncoded).thenReturn("recovered");
        assertEquals("recovered", retry.call("Create seller key", request));
        verify(request, times(5)).send();
        InOrder order = inOrder(pause);
        order.verify(pause).sleep(Duration.ofMillis(500));
        order.verify(pause).sleep(Duration.ofMillis(1000));
        order.verify(pause, times(2)).sleep(Duration.ofMillis(1500));
        order.verifyNoMoreInteractions();
    }

    @Test
    void stopsAfterTheLastAttemptAndReportsTheStatusWithoutPayload() throws Exception {
        HttpResponseException unavailable = status(503, "ID_GENERATION_UNAVAILABLE");
        when(request.send()).thenThrow(unavailable);
        SeedingException failure = assertThrows(SeedingException.class, () -> retry.call("Create seller key", request));
        assertEquals("Create seller key failed on attempt 5: HTTP 503 ID_GENERATION_UNAVAILABLE", failure.getMessage());
        assertSame(unavailable, failure.getCause());
        verify(request, times(5)).send();
        verify(pause, times(4)).sleep(any());
    }

    @Test
    void namesOnlyTheTypeOfAFinalTransportFailure() throws Exception {
        RetryPolicy single = new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1), pause);
        IOException closed = new IOException("connection closed with response bytes");
        when(request.send()).thenThrow(closed);
        SeedingException failure = assertThrows(SeedingException.class, () -> single.call("Read seller key", request));
        assertEquals("Read seller key failed on attempt 1: IOException", failure.getMessage());
        assertSame(closed, failure.getCause());
        verifyNoInteractions(pause);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422, 500})
    void neverRetriesOtherStatuses(int status) throws Exception {
        HttpResponseException rejected = status(status, null);
        when(request.send()).thenThrow(rejected);
        SeedingException failure = assertThrows(SeedingException.class, () -> retry.call("Look up product key", request));
        assertEquals("Look up product key failed on attempt 1: HTTP " + status, failure.getMessage());
        verify(request).send();
        verifyNoInteractions(pause);
    }

    @Test
    void neverRetriesInvalidResponses() throws Exception {
        HttpResponseException unavailable = status(503, null);
        when(request.send()).thenThrow(unavailable)
                .thenThrow(new IllegalArgumentException("Seller response does not match its definition"));
        SeedingException failure = assertThrows(SeedingException.class, () -> retry.call("Read seller key", request));
        assertEquals("Read seller key failed on attempt 2: Seller response does not match its definition", failure.getMessage());
        verify(request, times(2)).send();
        verify(pause).sleep(Duration.ofMillis(500));
    }

    @Test
    void propagatesInterruptionFromTheRequestAndThePause() throws Exception {
        InterruptedException interrupted = new InterruptedException();
        when(request.send()).thenThrow(interrupted);
        assertSame(interrupted, assertThrows(InterruptedException.class, () -> retry.call("Create product key", request)));
        verifyNoInteractions(pause);
        InterruptedException paused = new InterruptedException();
        HttpResponseException unavailable = status(503, null);
        doThrow(unavailable).when(request).send();
        doThrow(paused).when(pause).sleep(Duration.ofMillis(500));
        assertSame(paused, assertThrows(InterruptedException.class, () -> retry.call("Create product key", request)));
        verify(request, times(2)).send();
    }

    @Test
    void rejectsUnboundedOrInvalidConfiguration() throws Exception {
        Duration second = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, second, second, pause));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(11, second, second, pause));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ZERO, second, pause));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, second.negated(), second, pause));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, second, Duration.ofMillis(999), pause));
        assertThrows(NullPointerException.class, () -> new RetryPolicy(1, second, second, null));
        assertEquals("ok", new RetryPolicy(10, second, second, pause).call("Read seller key", () -> "ok"));
        verifyNoInteractions(pause);
    }

    private static HttpResponseException status(int status, String code) {
        HttpResponseException failure = mock();
        when(failure.statusCode()).thenReturn(status);
        when(failure.errorCode()).thenReturn(Optional.ofNullable(code));
        return failure;
    }
}
