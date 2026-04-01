package dev.nklip.javacraft.soap2rest.rest.app.service;

import java.math.BigDecimal;
import java.sql.Date;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.app.dao.GasMetricDao;
import dev.nklip.javacraft.soap2rest.rest.app.dao.MeterDao;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class GasServiceTest {

    @Mock
    MetricService metricService;

    @Mock
    MetricValidationService metricValidationService;

    @Mock
    GasMetricDao gasMetricDao;

    @Mock
    MeterDao meterDao;

    @Test
    public void testDeleteAllByAccountId() {
        GasService gasService = new GasService(
                metricService,
                metricValidationService,
                gasMetricDao,
                meterDao
        );
        when(gasMetricDao.deleteByAccountId(1L)).thenReturn(2);

        int deleted = gasService.deleteAllByAccountId(1L);

        Assertions.assertEquals(2, deleted);
        verify(gasMetricDao).deleteByAccountId(1L);
    }

    @Test
    public void testDeleteAllByAccountIdWhenNoMetricsFound() {
        GasService gasService = new GasService(
                metricService,
                metricValidationService,
                gasMetricDao,
                meterDao
        );
        when(gasMetricDao.deleteByAccountId(7L)).thenReturn(0);

        int deleted = gasService.deleteAllByAccountId(7L);

        Assertions.assertEquals(0, deleted);
        verify(gasMetricDao).deleteByAccountId(7L);
    }

    @Test
    public void testSubmitThrowsWhenMeterDoesNotBelongToAccount() {
        GasService gasService = new GasService(
                metricService,
                metricValidationService,
                gasMetricDao,
                meterDao
        );
        Metric metric = new Metric();
        metric.setMeterId(100L);
        metric.setReading(new BigDecimal("10.000"));
        metric.setDate(Date.valueOf("2024-01-15"));
        when(meterDao.existsByIdAndAccountId(100L, 2L)).thenReturn(false);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> gasService.submit(2L, metric)
        );

        verify(gasMetricDao, never()).save(any());
        verify(metricValidationService, never()).validate(any(), any());
    }

    @Test
    public void testSubmitSavesMetricWhenMeterBelongsToAccount() {
        GasService gasService = new GasService(
                metricService,
                metricValidationService,
                gasMetricDao,
                meterDao
        );
        Metric metric = new Metric();
        metric.setMeterId(100L);
        metric.setReading(new BigDecimal("10.000"));
        metric.setDate(Date.valueOf("2024-01-15"));
        when(meterDao.existsByIdAndAccountId(100L, 1L)).thenReturn(true);

        Metric response = gasService.submit(1L, metric);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(100L, response.getMeterId());
        Assertions.assertEquals(new BigDecimal("10.000"), response.getReading());
        Assertions.assertEquals(Date.valueOf("2024-01-15"), response.getDate());
        verify(metricValidationService).validate(isNull(), eq(metric));
        verify(gasMetricDao).save(any());
    }
}
