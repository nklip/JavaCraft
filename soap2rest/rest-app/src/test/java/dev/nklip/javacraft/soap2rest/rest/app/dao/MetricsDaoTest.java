package dev.nklip.javacraft.soap2rest.rest.app.dao;

import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import dev.nklip.javacraft.soap2rest.rest.app.service.ElectricService;
import dev.nklip.javacraft.soap2rest.rest.app.service.GasService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsDaoTest {

    @Mock
    private ElectricService electricService;

    @Mock
    private GasService gasService;

    @Test
    void testFindLatestMetricsReturnsEmptyListsWhenLatestValuesAreMissing() {
        MetricsDao dao = new MetricsDao(electricService, gasService);
        when(electricService.findLatestMetric(1L)).thenReturn(null);
        when(gasService.findLatestMetric(1L)).thenReturn(null);

        Metrics metrics = dao.findLatestMetrics(1L);

        Assertions.assertEquals(1L, metrics.getAccountId());
        Assertions.assertNotNull(metrics.getElecReadings());
        Assertions.assertNotNull(metrics.getGasReadings());
        Assertions.assertTrue(metrics.getElecReadings().isEmpty());
        Assertions.assertTrue(metrics.getGasReadings().isEmpty());
    }

    @Test
    void testFindLatestMetricsKeepsPresentMetricAndSkipsMissingMetric() {
        MetricsDao dao = new MetricsDao(electricService, gasService);
        Metric electricMetric = new Metric();
        electricMetric.setMeterId(200L);
        when(electricService.findLatestMetric(2L)).thenReturn(electricMetric);
        when(gasService.findLatestMetric(2L)).thenReturn(null);

        Metrics metrics = dao.findLatestMetrics(2L);

        Assertions.assertEquals(1, metrics.getElecReadings().size());
        Assertions.assertSame(electricMetric, metrics.getElecReadings().getFirst());
        Assertions.assertTrue(metrics.getGasReadings().isEmpty());
    }
}
