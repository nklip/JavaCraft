package dev.nklip.javacraft.soap2rest.rest.app.service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.repository.ElectricMetricRepository;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.repository.MeterRepository;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.entity.ElectricMetric;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.entity.MetricEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ElectricService {

    private final MetricService metricService;
    private final MetricValidationService metricValidationService;
    private final ElectricMetricRepository electricMetricRepository;
    private final MeterRepository meterRepository;

    public List<Metric> getMetricsByAccountId(Long accountId) {
        return metricService.calculateExtraFields(electricMetricRepository.findMetrics(accountId));
    }

    public Metric findLatestMetric(Long accountId) {
        return Optional.of(getMetricsByAccountId(accountId))
                .filter(l -> !l.isEmpty())
                .map(List::getLast)
                .orElse(null);
    }

    public Metric submit(Long accountId, Metric submittedMetric) {
        if (!meterRepository.existsByIdAndAccountId(submittedMetric.getMeterId(), accountId)) {
            throw new IllegalArgumentException("Meter is not linked to account.");
        }

        Metric latestMetric = Optional.ofNullable(electricMetricRepository
                .findTopByMeterIdInOrderByDateDesc(
                        Collections.singletonList(submittedMetric.getMeterId())
                ))
                .map(MetricEntity::toApiMetric)
                .orElse(null);

        metricValidationService.validate(latestMetric, submittedMetric);

        ElectricMetric electricMetric = new ElectricMetric();
        electricMetric.setMeterId(submittedMetric.getMeterId());
        electricMetric.setReading(submittedMetric.getReading());
        electricMetric.setDate(submittedMetric.getDate());

        electricMetricRepository.save(electricMetric);

        return electricMetric.toApiMetric();
    }

    public int deleteAllByAccountId(Long accountId) {
        return electricMetricRepository.deleteByAccountId(accountId);
    }
}
