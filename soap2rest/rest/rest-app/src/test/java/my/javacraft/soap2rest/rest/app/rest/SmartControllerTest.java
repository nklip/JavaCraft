package my.javacraft.soap2rest.rest.app.rest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.soap2rest.rest.api.AsyncJobResultResponse;
import my.javacraft.soap2rest.rest.api.Metric;
import my.javacraft.soap2rest.rest.api.Metrics;
import my.javacraft.soap2rest.rest.app.dao.MetricsDao;
import my.javacraft.soap2rest.rest.app.service.async.AsyncJobProcessingStatus;
import my.javacraft.soap2rest.rest.app.service.async.AsyncJobState;
import my.javacraft.soap2rest.rest.app.service.async.AsyncMetricsStorage;
import my.javacraft.soap2rest.rest.app.service.SmartService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SmartControllerTest {

    @Mock
    MetricsDao metricsDao;

    @Mock
    SmartService smartService;

    @Mock
    AsyncMetricsStorage asyncMetricsStorage;

    SmartController smartController;

    @BeforeEach
    public void beforeEach() {
        this.smartController = new SmartController(metricsDao, asyncMetricsStorage, smartService);
        this.smartController.setSmartMessage("Hello World!");
    }

    @Test
    public void testGetDefaultMessage() {
        ResponseEntity<String> response = smartController.getDefault();

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals("Hello World!", response.getBody());
    }

    @Test
    public void testGetMetrics() {
        when(metricsDao.findByAccountId(eq(111L))).thenReturn(createMetrics());

        ResponseEntity<Metrics> response = smartController.getMetrics(111L);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(111L, response.getBody().getAccountId());
    }

    @Test
    public void testGetLatestMetrics() {
        when(metricsDao.findLatestMetrics(eq(111L))).thenReturn(createMetrics());

        ResponseEntity<Metrics> response = smartController.getLatestMetrics(111L);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(111L, response.getBody().getAccountId());
    }

    @Test
    public void testUpdateMetrics() {
        when(smartService.submit(anyLong(), any())).thenReturn(Boolean.TRUE);

        ResponseEntity<?> response = smartController.updateMetrics(111L, createMetrics(), false);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(Boolean.TRUE, response.getBody());
        verify(smartService).submit(eq(111L), any(Metrics.class));
    }

    @Test
    public void testUpdateMetricsUsesPathAccountId() {
        Metrics metrics = createMetrics();
        metrics.setAccountId(999L);
        when(smartService.submit(anyLong(), any())).thenReturn(Boolean.TRUE);

        ResponseEntity<?> response = smartController.updateMetrics(111L, metrics, false);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(Boolean.TRUE, response.getBody());
        verify(smartService).submit(eq(111L), same(metrics));
    }

    @Test
    public void testUpdateMetricsAsync() {
        when(asyncMetricsStorage.submit(anyLong(), any())).thenReturn("req-1");

        ResponseEntity<?> response = smartController.updateMetrics(111L, createMetrics(), true);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(202, response.getStatusCode().value());
        Assertions.assertEquals("req-1", response.getBody());
        verify(asyncMetricsStorage).submit(eq(111L), any(Metrics.class));
        verifyNoInteractions(smartService);
    }

    @Test
    public void testGetAsyncResultAccepted() {
        AsyncJobState result = new AsyncJobState("req-1", AsyncJobProcessingStatus.ACCEPTED, null, null);
        when(asyncMetricsStorage.findResult("req-1")).thenReturn(java.util.Optional.of(result));

        ResponseEntity<AsyncJobResultResponse> response = smartController.getAsyncResult("req-1");

        Assertions.assertNotNull(response);
        Assertions.assertEquals(202, response.getStatusCode().value());
        Assertions.assertEquals(new AsyncJobResultResponse("req-1", null, null), response.getBody());
    }

    @Test
    public void testGetAsyncResultCompleted() {
        AsyncJobState result = new AsyncJobState("req-2", AsyncJobProcessingStatus.COMPLETED, Boolean.TRUE, null);
        when(asyncMetricsStorage.findResult("req-2")).thenReturn(java.util.Optional.of(result));

        ResponseEntity<AsyncJobResultResponse> response = smartController.getAsyncResult("req-2");

        Assertions.assertNotNull(response);
        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertEquals(new AsyncJobResultResponse("req-2", Boolean.TRUE, null), response.getBody());
    }

    @Test
    public void testGetAsyncResultFailed() {
        AsyncJobState result = new AsyncJobState("req-3", AsyncJobProcessingStatus.FAILED, null, "boom");
        when(asyncMetricsStorage.findResult("req-3")).thenReturn(java.util.Optional.of(result));

        ResponseEntity<AsyncJobResultResponse> response = smartController.getAsyncResult("req-3");

        Assertions.assertNotNull(response);
        Assertions.assertEquals(500, response.getStatusCode().value());
        Assertions.assertEquals(new AsyncJobResultResponse("req-3", null, "boom"), response.getBody());
    }

    @Test
    public void testGetAsyncResultNotFound() {
        when(asyncMetricsStorage.findResult("missing")).thenReturn(java.util.Optional.empty());

        ResponseEntity<AsyncJobResultResponse> response = smartController.getAsyncResult("missing");

        Assertions.assertNotNull(response);
        Assertions.assertEquals(404, response.getStatusCode().value());
        Assertions.assertNull(response.getBody());
    }

    @Test
    public void testDeleteAllMetrics() {
        when(smartService.deleteAllByAccountId(111L)).thenReturn(4);

        ResponseEntity<Integer> response = smartController.deleteAllMetrics(111L);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(4, response.getBody());
    }

    private Metrics createMetrics() {
        Metric electricMetric = new Metric();
        electricMetric.setId(123L);
        electricMetric.setMeterId(1L);
        electricMetric.setReading(new BigDecimal(23));

        List<Metric> electricList = new ArrayList<>();
        electricList.add(electricMetric);

        Metric gasMetric = new Metric();
        gasMetric.setId(124L);
        gasMetric.setMeterId(2L);
        gasMetric.setReading(new BigDecimal(24));

        List<Metric> gasList = new ArrayList<>();
        gasList.add(gasMetric);

        Metrics metrics = new Metrics();
        metrics.setAccountId(111L);
        metrics.setGasReadings(gasList);
        metrics.setElecReadings(electricList);

        return metrics;
    }

}
