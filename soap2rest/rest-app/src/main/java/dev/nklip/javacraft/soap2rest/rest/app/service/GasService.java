package dev.nklip.javacraft.soap2rest.rest.app.service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.repository.GasMetricRepository;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.repository.MeterRepository;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.entity.GasMetric;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.entity.MetricEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GasService {

    private final MetricService metricService;
    private final MetricValidationService metricValidationService;
    private final GasMetricRepository gasMetricRepository;
    private final MeterRepository meterRepository;

    public List<Metric> getMetricsByAccountId(Long accountId) {
        return metricService.calculateExtraFields(gasMetricRepository.findMetrics(accountId));
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

        Metric latestMetric = Optional.ofNullable(gasMetricRepository
                .findTopByMeterIdInOrderByDateDesc(
                        Collections.singletonList(submittedMetric.getMeterId())
                )).map(MetricEntity::toApiMetric).orElse(null);

        metricValidationService.validate(latestMetric, submittedMetric);

        GasMetric gasMetric = new GasMetric();
        gasMetric.setMeterId(submittedMetric.getMeterId());
        gasMetric.setReading(submittedMetric.getReading());
        gasMetric.setDate(submittedMetric.getDate());

        gasMetricRepository.save(gasMetric);

        return gasMetric.toApiMetric();
    }

    public int deleteAllByAccountId(Long accountId) {
        return gasMetricRepository.deleteByAccountId(accountId);
    }
}
