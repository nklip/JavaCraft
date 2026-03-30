package my.javacraft.soap2rest.rest.app.service.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.soap2rest.rest.app.service.SmartService;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Service;

/*
 * Where it fits async flow:
 *
 * 1. AsyncMetricsStorage.submit(accountId, metrics)
 *    -> generate requestId
 *    -> store status = ACCEPTED
 *    -> serialize AsyncMetrics
 *    -> send command to JMS queue
 *    -> return requestId
 *
 * 2. JMS queue
 *    -> keeps the message until a listener consumes it
 *
 * 3. AsyncMetricsListener.handleAsyncRequest(payload)               <----------- THERE
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
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncMetricsListener {

    private final AsyncMetricsStorage asyncMetricsStorage;
    private final SmartService smartService;
    private final ObjectMapper objectMapper;

    @JmsListener(destination = AsyncMetricsStorage.SMART_ASYNC_QUEUE)
    public void handleAsyncRequest(String payload) {
        AsyncMetrics command = deserialize(payload);

        try {
            boolean result = smartService.submit(command.getAccountId(), command.getMetrics());
            asyncMetricsStorage.storeCompleted(command.getRequestId(), result);
        } catch (RuntimeException ex) {
            log.error("Async smart request '{}' failed", command.getRequestId(), ex);
            asyncMetricsStorage.storeFailed(command.getRequestId(), ex.getMessage());
        }
    }

    private AsyncMetrics deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, AsyncMetrics.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize smart async request", ex);
        }
    }
}
