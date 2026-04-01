package dev.nklip.javacraft.soap2rest.rest.app.dao;

import java.util.List;
import dev.nklip.javacraft.soap2rest.rest.app.dao.entity.ElectricMetric;
import dev.nklip.javacraft.soap2rest.rest.app.dao.entity.MetricEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ElectricMetricDao extends JpaRepository<ElectricMetric, Long>, MetricEntityDao {

    @Query(value = """
            SELECT e FROM Account a, Meter m, ElectricMetric e
            WHERE a.id = m.accountId AND m.id = e.meterId AND a.id = :id
            ORDER by e.date ASC
            """)
    List<MetricEntity> findByAccountId(@Param("id") Long id);

    @Query(value = """
            SELECT e FROM ElectricMetric e
            WHERE e.meterId IN :ids
            ORDER by e.date ASC
           """)
    List<MetricEntity> findByMeterIds(@Param("ids") List<Long> ids);

    MetricEntity findTopByMeterIdInOrderByDateDesc(List<Long> ids);

    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM ElectricMetric e
            WHERE e.meterId IN (
                SELECT m.id
                FROM Meter m
                WHERE m.accountId = :id
            )
            """)
    int deleteByAccountId(@Param("id") Long accountId);
}
