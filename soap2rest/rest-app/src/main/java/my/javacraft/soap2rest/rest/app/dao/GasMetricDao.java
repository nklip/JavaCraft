package my.javacraft.soap2rest.rest.app.dao;

import java.util.List;
import my.javacraft.soap2rest.rest.app.dao.entity.GasMetric;
import my.javacraft.soap2rest.rest.app.dao.entity.MetricEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface GasMetricDao extends JpaRepository<GasMetric, Long>, MetricEntityDao {

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
