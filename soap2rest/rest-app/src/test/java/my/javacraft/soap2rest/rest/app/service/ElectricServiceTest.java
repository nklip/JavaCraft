package my.javacraft.soap2rest.rest.app.service;

import java.math.BigDecimal;
import java.sql.Date;
import my.javacraft.soap2rest.rest.api.Metric;
import my.javacraft.soap2rest.rest.app.dao.ElectricMetricDao;
import my.javacraft.soap2rest.rest.app.dao.MeterDao;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ElectricServiceTest {

    @Mock
    MetricService metricService;

    @Mock
    MetricValidationService metricValidationService;

    @Mock
    ElectricMetricDao electricMetricDao;

    @Mock
    MeterDao meterDao;

    @Test
    public void testDeleteAllByAccountId() {
        ElectricService electricService = new ElectricService(
                metricService,
                metricValidationService,
                electricMetricDao,
                meterDao
        );
        when(electricMetricDao.deleteByAccountId(1L)).thenReturn(2);

        int deleted = electricService.deleteAllByAccountId(1L);

        Assertions.assertEquals(2, deleted);
        verify(electricMetricDao).deleteByAccountId(1L);
    }

    @Test
    public void testDeleteAllByAccountIdWhenNoMetricsFound() {
        ElectricService electricService = new ElectricService(
                metricService,
                metricValidationService,
                electricMetricDao,
                meterDao
        );
        when(electricMetricDao.deleteByAccountId(7L)).thenReturn(0);

        int deleted = electricService.deleteAllByAccountId(7L);

        Assertions.assertEquals(0, deleted);
        verify(electricMetricDao).deleteByAccountId(7L);
    }

    @Test
    public void testSubmitThrowsWhenMeterDoesNotBelongToAccount() {
        ElectricService electricService = new ElectricService(
                metricService,
                metricValidationService,
                electricMetricDao,
                meterDao
        );
        Metric metric = new Metric();
        metric.setMeterId(100L);
        metric.setReading(new BigDecimal("10.000"));
        metric.setDate(Date.valueOf("2024-01-15"));
        when(meterDao.existsByIdAndAccountId(100L, 2L)).thenReturn(false);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> electricService.submit(2L, metric)
        );

        verify(electricMetricDao, never()).save(any());
        verify(metricValidationService, never()).validate(any(), any());
    }

    @Test
    public void testSubmitSavesMetricWhenMeterBelongsToAccount() {
        ElectricService electricService = new ElectricService(
                metricService,
                metricValidationService,
                electricMetricDao,
                meterDao
        );
        Metric metric = new Metric();
        metric.setMeterId(100L);
        metric.setReading(new BigDecimal("10.000"));
        metric.setDate(Date.valueOf("2024-01-15"));
        when(meterDao.existsByIdAndAccountId(100L, 1L)).thenReturn(true);

        Metric response = electricService.submit(1L, metric);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(100L, response.getMeterId());
        Assertions.assertEquals(new BigDecimal("10.000"), response.getReading());
        Assertions.assertEquals(Date.valueOf("2024-01-15"), response.getDate());
        verify(metricValidationService).validate(isNull(), eq(metric));
        verify(electricMetricDao).save(any());
    }
}
