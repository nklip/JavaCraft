package my.javacraft.soap2rest.rest.app.service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import my.javacraft.soap2rest.rest.api.Metric;
import my.javacraft.soap2rest.rest.api.Metrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SmartService {

    private final GasService gasService;
    private final ElectricService electricService;

    @Transactional
    public boolean submit(Long accountId, Metrics metrics) {
        List<Metric> gasMetricList = Optional.ofNullable(metrics)
                .map(Metrics::getGasReadings)
                .orElse(Collections.emptyList());
        List<Metric> electricMetricList = Optional.ofNullable(metrics)
                .map(Metrics::getElecReadings)
                .orElse(Collections.emptyList());

        for (Metric gasMetric : gasMetricList) {
            gasService.submit(accountId, gasMetric);
        }
        for (Metric electricMetric : electricMetricList) {
            electricService.submit(accountId, electricMetric);
        }
        return true;
    }

    @Transactional
    public int deleteAllByAccountId(Long accountId) {
        return gasService.deleteAllByAccountId(accountId)
                + electricService.deleteAllByAccountId(accountId);
    }
}
