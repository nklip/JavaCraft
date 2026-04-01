package dev.nklip.javacraft.soap2rest.rest.app.service.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.core.JmsTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncMetricsStorageTest {

    @Mock
    private JmsTemplate jmsTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    void testSubmitShouldStoreAcceptedResultAndSendJmsMessage() throws Exception {
        AsyncMetricsStorage asyncService = new AsyncMetricsStorage(jmsTemplate, objectMapper);
        Metrics metrics = createMetrics();
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation ->
                new ObjectMapper().writeValueAsString(invocation.getArgument(0))
        );

        String requestId = asyncService.submit(7L, metrics);

        Assertions.assertNotNull(requestId);
        AsyncJobState storedResult = asyncService.findResult(requestId).orElseThrow();
        Assertions.assertEquals(AsyncJobProcessingStatus.ACCEPTED, storedResult.status());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(jmsTemplate).convertAndSend(eq(AsyncMetricsStorage.SMART_ASYNC_QUEUE), payloadCaptor.capture());

        JsonNode payload = new ObjectMapper().readTree(payloadCaptor.getValue());
        Assertions.assertEquals(requestId, payload.get("requestId").asText());
        Assertions.assertEquals(7L, payload.get("accountId").asLong());
    }

    @Test
    void testSubmitShouldRemoveAcceptedResultWhenSerializationFails() throws Exception {
        AsyncMetricsStorage asyncService = new AsyncMetricsStorage(jmsTemplate, objectMapper);
        AtomicReference<String> requestId = new AtomicReference<>();
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            AsyncMetrics command = invocation.getArgument(0);
            requestId.set(command.getRequestId());
            throw new JsonProcessingException("broken") { };
        });

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> asyncService.submit(7L, createMetrics())
        );

        Assertions.assertEquals("Unable to serialize smart async request", exception.getMessage());
        Assertions.assertNotNull(requestId.get());
        Assertions.assertTrue(asyncService.findResult(requestId.get()).isEmpty());
    }

    @Test
    void testSubmitShouldRemoveAcceptedResultWhenJmsSendFails() throws Exception {
        AsyncMetricsStorage asyncService = new AsyncMetricsStorage(jmsTemplate, objectMapper);
        AtomicReference<String> requestId = new AtomicReference<>();
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            AsyncMetrics command = invocation.getArgument(0);
            requestId.set(command.getRequestId());
            return new ObjectMapper().writeValueAsString(command);
        });
        doThrow(new IllegalStateException("broker down"))
                .when(jmsTemplate)
                .convertAndSend(eq(AsyncMetricsStorage.SMART_ASYNC_QUEUE), any(String.class));

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> asyncService.submit(7L, createMetrics())
        );

        Assertions.assertEquals("broker down", exception.getMessage());
        Assertions.assertNotNull(requestId.get());
        Assertions.assertTrue(asyncService.findResult(requestId.get()).isEmpty());
    }

    @Test
    void testStoreCompletedShouldUpdateResult() {
        AsyncMetricsStorage asyncService = new AsyncMetricsStorage(jmsTemplate, objectMapper);

        asyncService.storeCompleted("req-7", true);

        AsyncJobState storedResult = asyncService.findResult("req-7").orElseThrow();
        Assertions.assertEquals(AsyncJobProcessingStatus.COMPLETED, storedResult.status());
        Assertions.assertEquals(Boolean.TRUE, storedResult.result());
        Assertions.assertNull(storedResult.errorMessage());
    }

    @Test
    void testStoreFailedShouldUpdateResult() {
        AsyncMetricsStorage asyncService = new AsyncMetricsStorage(jmsTemplate, objectMapper);

        asyncService.storeFailed("req-9", "boom");

        AsyncJobState storedResult = asyncService.findResult("req-9").orElseThrow();
        Assertions.assertEquals(AsyncJobProcessingStatus.FAILED, storedResult.status());
        Assertions.assertNull(storedResult.result());
        Assertions.assertEquals("boom", storedResult.errorMessage());
    }

    @Test
    void testFindResultShouldReturnEmptyWhenRequestIdIsUnknown() {
        AsyncMetricsStorage asyncService = new AsyncMetricsStorage(jmsTemplate, objectMapper);

        Assertions.assertTrue(asyncService.findResult("missing").isEmpty());
    }

    private Metrics createMetrics() {
        Metric gasMetric = new Metric();
        gasMetric.setId(1L);
        gasMetric.setMeterId(101L);
        gasMetric.setReading(new BigDecimal("12.300"));
        gasMetric.setDate(Date.valueOf("2024-01-10"));

        Metric electricMetric = new Metric();
        electricMetric.setId(2L);
        electricMetric.setMeterId(202L);
        electricMetric.setReading(new BigDecimal("45.600"));
        electricMetric.setDate(Date.valueOf("2024-01-11"));

        Metrics metrics = new Metrics();
        metrics.setAccountId(7L);
        metrics.setGasReadings(List.of(gasMetric));
        metrics.setElecReadings(List.of(electricMetric));
        return metrics;
    }
}
