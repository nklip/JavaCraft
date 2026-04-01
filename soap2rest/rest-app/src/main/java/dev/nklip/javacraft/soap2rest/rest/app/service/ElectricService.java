package dev.nklip.javacraft.soap2rest.rest.app.service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.app.dao.ElectricMetricDao;
import dev.nklip.javacraft.soap2rest.rest.app.dao.MeterDao;
import dev.nklip.javacraft.soap2rest.rest.app.dao.entity.ElectricMetric;
import dev.nklip.javacraft.soap2rest.rest.app.dao.entity.MetricEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ElectricService {

    private final MetricService metricService;
    private final MetricValidationService metricValidationService;
    private final ElectricMetricDao electricMetricDao;
    private final MeterDao meterDao;

    public List<Metric> getMetricsByAccountId(Long accountId) {
        return metricService.calculateExtraFields(electricMetricDao.findMetrics(accountId));
    }

    public Metric findLatestMetric(Long accountId) {
        return Optional.of(getMetricsByAccountId(accountId))
                .filter(l -> !l.isEmpty())
                .map(List::getLast)
                .orElse(null);
    }

    public Metric submit(Long accountId, Metric submittedMetric) {
        if (!meterDao.existsByIdAndAccountId(submittedMetric.getMeterId(), accountId)) {
            throw new IllegalArgumentException("Meter is not linked to account.");
        }

        Metric latestMetric = Optional.ofNullable(electricMetricDao
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

        electricMetricDao.save(electricMetric);

        return electricMetric.toApiMetric();
    }

    public int deleteAllByAccountId(Long accountId) {
        return electricMetricDao.deleteByAccountId(accountId);
    }
}
