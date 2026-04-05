package dev.nklip.javacraft.soap2rest.rest.app.persistence;

import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import dev.nklip.javacraft.soap2rest.rest.app.service.ElectricService;
import dev.nklip.javacraft.soap2rest.rest.app.service.GasService;
import dev.nklip.javacraft.soap2rest.rest.app.service.MetricsQueryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsQueryServiceTest {

    @Mock
    private ElectricService electricService;

    @Mock
    private GasService gasService;

    @Test
    void testFindLatestMetricsReturnsEmptyListsWhenLatestValuesAreMissing() {
        MetricsQueryService dao = new MetricsQueryService(electricService, gasService);
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
        MetricsQueryService dao = new MetricsQueryService(electricService, gasService);
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
