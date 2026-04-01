package my.javacraft.soap2rest.rest.app.service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import my.javacraft.soap2rest.rest.api.Metric;
import my.javacraft.soap2rest.rest.app.dao.GasMetricDao;
import my.javacraft.soap2rest.rest.app.dao.MeterDao;
import my.javacraft.soap2rest.rest.app.dao.entity.GasMetric;
import my.javacraft.soap2rest.rest.app.dao.entity.MetricEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GasService {

    private final MetricService metricService;
    private final MetricValidationService metricValidationService;
    private final GasMetricDao gasMetricDao;
    private final MeterDao meterDao;

    public List<Metric> getMetricsByAccountId(Long accountId) {
        return metricService.calculateExtraFields(gasMetricDao.findMetrics(accountId));
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

        Metric latestMetric = Optional.ofNullable(gasMetricDao
                .findTopByMeterIdInOrderByDateDesc(
                        Collections.singletonList(submittedMetric.getMeterId())
                )).map(MetricEntity::toApiMetric).orElse(null);

        metricValidationService.validate(latestMetric, submittedMetric);

        GasMetric gasMetric = new GasMetric();
        gasMetric.setMeterId(submittedMetric.getMeterId());
        gasMetric.setReading(submittedMetric.getReading());
        gasMetric.setDate(submittedMetric.getDate());

        gasMetricDao.save(gasMetric);

        return gasMetric.toApiMetric();
    }

    public int deleteAllByAccountId(Long accountId) {
        return gasMetricDao.deleteByAccountId(accountId);
    }
}
