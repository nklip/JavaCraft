package my.javacraft.soap2rest.rest.app.dao;

import java.util.List;
import my.javacraft.soap2rest.rest.api.Metric;
import my.javacraft.soap2rest.rest.app.dao.entity.MetricEntity;

public interface MetricEntityDao {

    List<MetricEntity> findByAccountId(Long accountId);

    List<MetricEntity> findByMeterIds(List<Long> ids);

    MetricEntity findTopByMeterIdInOrderByDateDesc(List<Long> ids);

    default List<Metric> findMetrics(Long accountId) {
        return findByAccountId(accountId)
                .stream()
                .map(MetricEntity::toApiMetric)
                .toList();
    }
}
