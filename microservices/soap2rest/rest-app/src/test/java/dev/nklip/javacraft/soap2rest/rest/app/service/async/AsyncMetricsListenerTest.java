package dev.nklip.javacraft.soap2rest.rest.app.service.async;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import dev.nklip.javacraft.soap2rest.rest.app.service.SmartService;
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
    void testHandleAsyncRequestShouldStoreCompletedResult() {
        AsyncMetrics command = new AsyncMetrics("req-7", 7L, new Metrics());
        AsyncMetricsListener listener = new AsyncMetricsListener(asyncMetricsStorage, smartService, objectMapper);
        when(objectMapper.readValue("payload", AsyncMetrics.class)).thenReturn(command);
        when(smartService.submit(7L, command.getMetrics())).thenReturn(Boolean.TRUE);

        listener.handleAsyncRequest("payload");

        verify(asyncMetricsStorage).storeCompleted("req-7", true);
        verify(asyncMetricsStorage, never()).storeFailed(any(), any());
    }

    @Test
    void testHandleAsyncRequestShouldStoreFailedResult() {
        AsyncMetrics command = new AsyncMetrics("req-9", 7L, new Metrics());
        AsyncMetricsListener listener = new AsyncMetricsListener(asyncMetricsStorage, smartService, objectMapper);
        when(objectMapper.readValue("payload", AsyncMetrics.class)).thenReturn(command);
        when(smartService.submit(7L, command.getMetrics())).thenThrow(new IllegalStateException("boom"));

        listener.handleAsyncRequest("payload");

        verify(asyncMetricsStorage).storeFailed("req-9", "boom");
        verify(asyncMetricsStorage, never()).storeCompleted(any(), anyBoolean());
    }

    @Test
    void testHandleAsyncRequestShouldThrowWhenDeserializationFails() {
        AsyncMetricsListener listener = new AsyncMetricsListener(asyncMetricsStorage, smartService, objectMapper);
        when(objectMapper.readValue("payload", AsyncMetrics.class))
                .thenThrow(new JacksonException("broken") { });

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
