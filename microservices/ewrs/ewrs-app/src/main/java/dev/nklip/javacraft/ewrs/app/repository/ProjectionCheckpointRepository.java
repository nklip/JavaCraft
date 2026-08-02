package dev.nklip.javacraft.ewrs.app.repository;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists the last applied event-store id for a projector instance.
 * Architecture mapping: consulted by {@code ProjectionCoordinator} before catch-up and written by
 * {@code ProjectionApplier} after each projection step in the Projection Flow.
 */
@Repository
@SuppressWarnings("unused")
public class ProjectionCheckpointRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ProjectionCheckpointRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long getLastAppliedEventId(String projectionName) {
        return jdbcTemplate.query("""
                select last_event_store_id
                from projection_checkpoint
                where projection_name = :projectionName
                """,
                        Map.of("projectionName", projectionName),
                        (resultSet, rowNumber) -> resultSet.getLong("last_event_store_id")
                )
                .stream()
                .findFirst()
                .orElse(0L);
    }

    public void save(String projectionName, long lastEventStoreId) {
        jdbcTemplate.update("""
                insert into projection_checkpoint (projection_name, last_event_store_id)
                values (:projectionName, :lastEventStoreId)
                on conflict (projection_name) do update
                set last_event_store_id = excluded.last_event_store_id
                """, new MapSqlParameterSource()
                .addValue("projectionName", projectionName)
                .addValue("lastEventStoreId", lastEventStoreId)
        );
    }

    public void reset(String projectionName) {
        save(projectionName, 0L);
    }
}
