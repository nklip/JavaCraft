package dev.nklip.javacraft.soap2rest.rest.app.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.app.service.GasService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;

@ExtendWith(MockitoExtension.class)
public class GasControllerTest {

    @Mock
    GasService gasService;

    @Test
    public void testGetGasMetrics() {
        GasController gasController = new GasController(gasService);

        when(gasService.getMetricsByAccountId(anyLong())).thenReturn(createMetricList());

        ResponseEntity<List<Metric>> response = gasController.getGasMetrics(1L);
        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(1, response.getBody().size());
        verify(gasService, atLeastOnce()).getMetricsByAccountId(anyLong());
    }

    @Test
    public void testGetLatestGasMetric() {
        GasController gasController = new GasController(gasService);

        when(gasService.findLatestMetric(anyLong())).thenReturn(createMetricList().getFirst());

        ResponseEntity<Metric> response = gasController.getLatestGasMetric(1L);
        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        verify(gasService, atLeastOnce()).findLatestMetric(anyLong());
    }

    @Test
    public void testPutNewGasMetric() {
        GasController gasController = new GasController(gasService);

        Metric metric = createMetricList().getFirst();
        when(gasService.submit(anyLong(), any())).thenReturn(metric);

        ResponseEntity<Metric> response = gasController.putNewGasMetric(1L, metric);
        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        verify(gasService, atLeastOnce()).submit(eq(1L), any());
    }

    @Test
    public void testDeleteAllGasMetrics() {
        GasController gasController = new GasController(gasService);

        when(gasService.deleteAllByAccountId(anyLong())).thenReturn(2);

        ResponseEntity<Integer> response = gasController.deleteAllGasMetrics(1L);
        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(2, response.getBody());
        verify(gasService, atLeastOnce()).deleteAllByAccountId(1L);
    }

    private List<Metric> createMetricList() {
        Metric metric = new Metric();
        metric.setId(123L);
        metric.setMeterId(1L);
        metric.setReading(new BigDecimal(23));

        List<Metric> metricList = new ArrayList<>();
        metricList.add(metric);
        return metricList;
    }
    
}
