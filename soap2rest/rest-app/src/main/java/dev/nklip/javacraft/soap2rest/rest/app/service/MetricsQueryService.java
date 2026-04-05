package dev.nklip.javacraft.soap2rest.rest.app.service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MetricsQueryService {

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
