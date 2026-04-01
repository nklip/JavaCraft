package dev.nklip.javacraft.soap2rest.rest.app.dao;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import dev.nklip.javacraft.soap2rest.rest.app.service.ElectricService;
import dev.nklip.javacraft.soap2rest.rest.app.service.GasService;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MetricsDao {

    private final ElectricService electricService;
    private final GasService gasService;

    public Metrics findByAccountId(Long accountId) {
        Metrics metrics = new Metrics();
        metrics.setAccountId(accountId);
        metrics.setElecReadings(electricService.getMetricsByAccountId(accountId));
        metrics.setGasReadings(gasService.getMetricsByAccountId(accountId));

        return metrics;
    }

    public Metrics findLatestMetrics(Long accountId) {
        Metrics metrics = new Metrics();
        metrics.setAccountId(accountId);
        metrics.setElecReadings(toLatestReadings(electricService.findLatestMetric(accountId)));
        metrics.setGasReadings(toLatestReadings(gasService.findLatestMetric(accountId)));

        return metrics;
    }

    private List<Metric> toLatestReadings(Metric latestMetric) {
        return Optional.ofNullable(latestMetric)
                .map(List::of)
                .orElse(Collections.emptyList());
    }

}
