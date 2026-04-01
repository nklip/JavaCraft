package my.javacraft.soap2rest.rest.app.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.soap2rest.rest.api.Metric;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MetricServiceTest {

    MetricService metricService = new MetricService();

    @Test
    public void testCalculateExtraFields() {
        List<Metric> metricList = new ArrayList<>();
        Metric metric1 = new Metric();
        metric1.setReading(new BigDecimal("50"));
        metric1.setDate(Date.valueOf("2023-04-01"));
        metricList.add(metric1);
        Metric metric2 = new Metric();
        metric2.setReading(new BigDecimal("110"));
        metric2.setDate(Date.valueOf("2023-05-01"));
        metricList.add(metric2);
        Metric metric3 = new Metric();
        metric3.setReading(new BigDecimal("220"));
        metric3.setDate(Date.valueOf("2023-06-01"));
        metricList.add(metric3);
        Metric metric4 = new Metric();
        metric4.setReading(new BigDecimal("450"));
        metric4.setDate(Date.valueOf("2023-07-01"));
        metricList.add(metric4);

        metricService.calculateExtraFields(metricList);

        Assertions.assertNull(metricList.getFirst().getUsageSinceLastRead());
        Assertions.assertNull(metricList.getFirst().getPeriodSinceLastRead());
        Assertions.assertNull(metricList.getFirst().getAvgDailyUsage());

        Assertions.assertEquals("60", metricList.get(1).getUsageSinceLastRead().toString());
        Assertions.assertEquals(30L, metricList.get(1).getPeriodSinceLastRead());
        Assertions.assertEquals("2.000", metricList.get(1).getAvgDailyUsage().toString());

        Assertions.assertEquals("110", metricList.get(2).getUsageSinceLastRead().toString());
        Assertions.assertEquals(31L, metricList.get(2).getPeriodSinceLastRead());
        Assertions.assertEquals("2.787", metricList.get(2).getAvgDailyUsage().toString());

        Assertions.assertEquals("230", metricList.get(3).getUsageSinceLastRead().toString());
        Assertions.assertEquals(30L, metricList.get(3).getPeriodSinceLastRead());
        Assertions.assertEquals("4.396", metricList.get(3).getAvgDailyUsage().toString());
    }

    @Test
    public void testCalculateExtraFieldsSeparatelyForEachMeter() {
        List<Metric> metricList = new ArrayList<>();

        Metric meter100First = new Metric();
        meter100First.setMeterId(100L);
        meter100First.setReading(new BigDecimal("10"));
        meter100First.setDate(Date.valueOf("2024-01-01"));
        metricList.add(meter100First);

        Metric meter200First = new Metric();
        meter200First.setMeterId(200L);
        meter200First.setReading(new BigDecimal("30"));
        meter200First.setDate(Date.valueOf("2024-01-02"));
        metricList.add(meter200First);

        Metric meter100Second = new Metric();
        meter100Second.setMeterId(100L);
        meter100Second.setReading(new BigDecimal("16"));
        meter100Second.setDate(Date.valueOf("2024-01-03"));
        metricList.add(meter100Second);

        Metric meter200Second = new Metric();
        meter200Second.setMeterId(200L);
        meter200Second.setReading(new BigDecimal("45"));
        meter200Second.setDate(Date.valueOf("2024-01-04"));
        metricList.add(meter200Second);

        metricService.calculateExtraFields(metricList);

        Assertions.assertNull(metricList.getFirst().getUsageSinceLastRead());
        Assertions.assertNull(metricList.getFirst().getPeriodSinceLastRead());
        Assertions.assertNull(metricList.getFirst().getAvgDailyUsage());

        Assertions.assertNull(metricList.get(1).getUsageSinceLastRead());
        Assertions.assertNull(metricList.get(1).getPeriodSinceLastRead());
        Assertions.assertNull(metricList.get(1).getAvgDailyUsage());

        Assertions.assertEquals("6", metricList.get(2).getUsageSinceLastRead().toString());
        Assertions.assertEquals(2L, metricList.get(2).getPeriodSinceLastRead());
        Assertions.assertEquals("3.000", metricList.get(2).getAvgDailyUsage().toString());

        Assertions.assertEquals("15", metricList.get(3).getUsageSinceLastRead().toString());
        Assertions.assertEquals(2L, metricList.get(3).getPeriodSinceLastRead());
        Assertions.assertEquals("7.500", metricList.get(3).getAvgDailyUsage().toString());
    }
}
