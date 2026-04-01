package dev.nklip.javacraft.soap2rest.rest.app.service.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

/*
 * AsyncMetricsStorage is responsible for async job orchestration and state storage.
 *
 * Where it fits async flow:
 *
 * 1. AsyncMetricsStorage.submit(accountId, metrics)                  <----------- THERE
 *    -> generate requestId
 *    -> store status = ACCEPTED
 *    -> serialize AsyncMetrics
 *    -> send command to JMS queue
 *    -> return requestId
 *
 * 2. JMS queue
 *    -> keeps the message until a listener consumes it
 *
 * 3. AsyncMetricsListener.handleAsyncRequest(payload)
 *    -> receives message from JMS
 *    -> deserializes payload into AsyncMetrics
 *
 * 4. SmartService.submit(accountId, metrics)
 *    -> processes gas and electric metrics
 *    -> on success: AsyncMetricsListener calls AsyncMetricsStorage.storeCompleted(requestId, true)
 *    -> on failure: AsyncMetricsListener calls AsyncMetricsStorage.storeFailed(requestId, errorMessage)
 *
 * 5. Poll result
 *    -> client calls GET /api/v1/smart/async/{requestId}
 *    -> SmartController asks AsyncMetricsStorage.findResult(requestId)
 *    -> response:
 *       ACCEPTED  -> HTTP 202
 *       COMPLETED -> HTTP 200
 *       FAILED    -> HTTP 500
 *       missing   -> HTTP 404
 */
@Service
@RequiredArgsConstructor
public class AsyncMetricsStorage {

    public static final String SMART_ASYNC_QUEUE = "smart-metrics-queue";

    private final ConcurrentMap<String, AsyncJobState> asyncResults = new ConcurrentHashMap<>();

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;

    public String submit(Long accountId, Metrics metrics) {
        String requestId = UUID.randomUUID().toString();
        storeAccepted(requestId);

        try {
            AsyncMetrics command = new AsyncMetrics(requestId, accountId, metrics);
            jmsTemplate.convertAndSend(SMART_ASYNC_QUEUE, objectMapper.writeValueAsString(command));
            return requestId;
        } catch (JsonProcessingException ex) {
            removeResult(requestId);
            throw new IllegalStateException("Unable to serialize smart async request", ex);
        } catch (RuntimeException ex) {
            removeResult(requestId);
            throw ex;
        }
    }

    public Optional<AsyncJobState> findResult(String requestId) {
        return Optional.ofNullable(asyncResults.get(requestId));
    }

    void storeCompleted(String requestId, boolean result) {
        asyncResults.put(requestId, new AsyncJobState(
                requestId,
                AsyncJobProcessingStatus.COMPLETED,
                result,
                null
        ));
    }

    void storeFailed(String requestId, String errorMessage) {
        asyncResults.put(requestId, new AsyncJobState(
                requestId,
                AsyncJobProcessingStatus.FAILED,
                null,
                errorMessage
        ));
    }

    private void storeAccepted(String requestId) {
        asyncResults.put(requestId, new AsyncJobState(
                requestId,
                AsyncJobProcessingStatus.ACCEPTED,
                null,
                null
        ));
    }

    private void removeResult(String requestId) {
        asyncResults.remove(requestId);
    }
}
