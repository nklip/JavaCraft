package my.javacraft.soap2rest.rest.app.service.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.javacraft.soap2rest.rest.api.Metrics;
import my.javacraft.soap2rest.rest.app.service.SmartService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncMetricsListenerTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private AsyncMetricsStorage asyncMetricsStorage;

    @Mock
    private SmartService smartService;

    @Test
    void testHandleAsyncRequestShouldStoreCompletedResult() throws Exception {
        AsyncMetrics command = new AsyncMetrics("req-7", 7L, new Metrics());
        AsyncMetricsListener listener = new AsyncMetricsListener(asyncMetricsStorage, smartService, objectMapper);
        when(objectMapper.readValue("payload", AsyncMetrics.class)).thenReturn(command);
        when(smartService.submit(7L, command.getMetrics())).thenReturn(Boolean.TRUE);

        listener.handleAsyncRequest("payload");

        verify(asyncMetricsStorage).storeCompleted("req-7", true);
        verify(asyncMetricsStorage, never()).storeFailed(any(), any());
    }

    @Test
    void testHandleAsyncRequestShouldStoreFailedResult() throws Exception {
        AsyncMetrics command = new AsyncMetrics("req-9", 7L, new Metrics());
        AsyncMetricsListener listener = new AsyncMetricsListener(asyncMetricsStorage, smartService, objectMapper);
        when(objectMapper.readValue("payload", AsyncMetrics.class)).thenReturn(command);
        when(smartService.submit(7L, command.getMetrics())).thenThrow(new IllegalStateException("boom"));

        listener.handleAsyncRequest("payload");

        verify(asyncMetricsStorage).storeFailed("req-9", "boom");
        verify(asyncMetricsStorage, never()).storeCompleted(any(), anyBoolean());
    }

    @Test
    void testHandleAsyncRequestShouldThrowWhenDeserializationFails() throws Exception {
        AsyncMetricsListener listener = new AsyncMetricsListener(asyncMetricsStorage, smartService, objectMapper);
        when(objectMapper.readValue("payload", AsyncMetrics.class))
                .thenThrow(new JsonProcessingException("broken") { });

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> listener.handleAsyncRequest("payload")
        );

        Assertions.assertEquals("Unable to deserialize smart async request", exception.getMessage());
        verify(smartService, never()).submit(any(), any());
        verify(asyncMetricsStorage, never()).storeCompleted(any(), anyBoolean());
        verify(asyncMetricsStorage, never()).storeFailed(any(), any());
    }
}
