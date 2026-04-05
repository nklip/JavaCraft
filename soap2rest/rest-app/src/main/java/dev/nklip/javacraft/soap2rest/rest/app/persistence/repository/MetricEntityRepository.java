package dev.nklip.javacraft.soap2rest.rest.app.persistence.repository;

import java.util.List;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.entity.MetricEntity;

public interface MetricEntityRepository {

    List<MetricEntity> findByAccountId(Long accountId);

    @SuppressWarnings("unused")
    List<MetricEntity> findByMeterIds(List<Long> ids);

    MetricEntity findTopByMeterIdInOrderByDateDesc(List<Long> ids);

    default List<Metric> findMetrics(Long accountId) {
        return findByAccountId(accountId)
                .stream()
                .map(MetricEntity::toApiMetric)
                .toList();
    }
}
