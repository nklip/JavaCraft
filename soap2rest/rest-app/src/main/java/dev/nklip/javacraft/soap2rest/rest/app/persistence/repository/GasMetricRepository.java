package dev.nklip.javacraft.soap2rest.rest.app.persistence.repository;

import java.util.List;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.entity.GasMetric;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.entity.MetricEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface GasMetricRepository extends JpaRepository<GasMetric, Long>, MetricEntityRepository {

    @Query(value = """
            SELECT g FROM Account a, Meter m, GasMetric g
            WHERE a.id = m.accountId AND m.id = g.meterId AND a.id = :id
            ORDER by g.date ASC
            """)
    List<MetricEntity> findByAccountId(@Param("id") Long id);

    @Query(value = """
            SELECT g FROM GasMetric g
            WHERE g.meterId IN :ids
            ORDER by g.date ASC
            """)
    List<MetricEntity> findByMeterIds(@Param("ids") List<Long> ids);

    MetricEntity findTopByMeterIdInOrderByDateDesc(List<Long> ids);

    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM GasMetric g
            WHERE g.meterId IN (
                SELECT m.id
                FROM Meter m
                WHERE m.accountId = :id
            )
            """)
    int deleteByAccountId(@Param("id") Long accountId);
}
