package my.javacraft.soap2rest.rest.app.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import my.javacraft.soap2rest.rest.api.Metric;
import my.javacraft.soap2rest.rest.api.Metrics;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SmartServiceTest {

    @Mock
    GasService gasService;

    @Mock
    ElectricService electricService;

    @Test
    public void testSubmitUsesAccountIdForElectricMetrics() {
        SmartService smartService = new SmartService(gasService, electricService);

        Metric gasMetric = new Metric();
        gasMetric.setMeterId(100L);
        gasMetric.setReading(new BigDecimal("5.000"));
        gasMetric.setDate(Date.valueOf("2024-01-14"));

        Metric electricMetric = new Metric();
        electricMetric.setMeterId(200L);
        electricMetric.setReading(new BigDecimal("10.000"));
        electricMetric.setDate(Date.valueOf("2024-01-15"));

        Metrics metrics = new Metrics();
        metrics.setAccountId(999L);
        metrics.setGasReadings(List.of(gasMetric));
        metrics.setElecReadings(List.of(electricMetric));

        boolean result = smartService.submit(1L, metrics);

        Assertions.assertTrue(result);
        verify(gasService).submit(1L, gasMetric);
        verify(electricService).submit(1L, electricMetric);
    }

    @Test
    public void testSubmitHandlesMissingGasReadings() {
        SmartService smartService = new SmartService(gasService, electricService);

        Metric electricMetric = new Metric();
        electricMetric.setMeterId(200L);
        electricMetric.setReading(new BigDecimal("10.000"));
        electricMetric.setDate(Date.valueOf("2024-01-15"));

        Metrics metrics = new Metrics();
        metrics.setElecReadings(List.of(electricMetric));

        boolean result = smartService.submit(1L, metrics);

        Assertions.assertTrue(result);
        verifyNoInteractions(gasService);
        verify(electricService).submit(1L, electricMetric);
    }

    @Test
    public void testSubmitHandlesMissingElectricReadings() {
        SmartService smartService = new SmartService(gasService, electricService);

        Metric gasMetric = new Metric();
        gasMetric.setMeterId(100L);
        gasMetric.setReading(new BigDecimal("5.000"));
        gasMetric.setDate(Date.valueOf("2024-01-14"));

        Metrics metrics = new Metrics();
        metrics.setGasReadings(List.of(gasMetric));

        boolean result = smartService.submit(1L, metrics);

        Assertions.assertTrue(result);
        verify(gasService).submit(1L, gasMetric);
        verifyNoInteractions(electricService);
    }

    @Test
    public void testSubmitHandlesMissingAllReadingLists() {
        SmartService smartService = new SmartService(gasService, electricService);
        Metrics metrics = new Metrics();

        boolean result = smartService.submit(1L, metrics);

        Assertions.assertTrue(result);
        verifyNoInteractions(gasService, electricService);
    }

    @Test
    public void testDeleteAllByAccountId() {
        SmartService smartService = new SmartService(gasService, electricService);
        when(gasService.deleteAllByAccountId(1L)).thenReturn(2);
        when(electricService.deleteAllByAccountId(1L)).thenReturn(3);

        int deleted = smartService.deleteAllByAccountId(1L);

        Assertions.assertEquals(5, deleted);
        verify(gasService).deleteAllByAccountId(1L);
        verify(electricService).deleteAllByAccountId(1L);
    }

    @Test
    public void testDeleteAllByAccountIdWhenNoMetricsFound() {
        SmartService smartService = new SmartService(gasService, electricService);
        when(gasService.deleteAllByAccountId(7L)).thenReturn(0);
        when(electricService.deleteAllByAccountId(7L)).thenReturn(0);

        int deleted = smartService.deleteAllByAccountId(7L);

        Assertions.assertEquals(0, deleted);
        verify(gasService).deleteAllByAccountId(7L);
        verify(electricService).deleteAllByAccountId(7L);
    }
}
