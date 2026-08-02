package dev.nklip.javacraft.ewrs.app.repository;

import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the SQL projection that represents current work-request state.
 * Architecture mapping: updated by {@code ProjectionApplier} in the Projection Flow and queried by the Read Side
 * request endpoints after projections catch up.
 */
@Repository
@SuppressWarnings("unused")
public class WorkRequestProjectionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public WorkRequestProjectionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(WorkRequestResponse response) {
        jdbcTemplate.update("""
                insert into work_request_projection (
                    task_id,
                    title,
                    priority,
                    budget_code,
                    estimate,
                    status,
                    requested_by,
                    last_actor,
                    reason,
                    last_event_id,
                    last_occurred_at,
                    stream_version
                ) values (
                    :taskId,
                    :title,
                    :priority,
                    :budgetCode,
                    :estimate,
                    :status,
                    :requestedBy,
                    :lastActor,
                    :reason,
                    :lastEventId,
                    :lastOccurredAt,
                    :streamVersion
                )
                on conflict (task_id) do update
                set title = excluded.title,
                    priority = excluded.priority,
                    budget_code = excluded.budget_code,
                    estimate = excluded.estimate,
                    status = excluded.status,
                    requested_by = excluded.requested_by,
                    last_actor = excluded.last_actor,
                    reason = excluded.reason,
                    last_event_id = excluded.last_event_id,
                    last_occurred_at = excluded.last_occurred_at,
                    stream_version = excluded.stream_version
                """, new MapSqlParameterSource()
                .addValue("taskId", response.requestId())
                .addValue("title", response.title())
                .addValue("priority", response.priority().name())
                .addValue("budgetCode", response.budgetCode())
                .addValue("estimate", response.estimate())
                .addValue("status", response.status().name())
                .addValue("requestedBy", response.requestedBy())
                .addValue("lastActor", response.lastActor())
                .addValue("reason", response.reason())
                .addValue("lastEventId", response.lastEventId())
                .addValue("lastOccurredAt", OffsetDateTime.ofInstant(response.lastOccurredAt(), ZoneOffset.UTC))
                .addValue("streamVersion", response.streamVersion()));
    }

    public Optional<WorkRequestResponse> findByRequestId(int requestId) {
        return jdbcTemplate.query("""
                select task_id, title, priority, budget_code, estimate, status, requested_by,
                       last_actor, reason, last_event_id, last_occurred_at, stream_version
                from work_request_projection
                where task_id = :requestId
                """, Map.of("requestId", requestId), (resultSet, rowNumber) -> mapRow(resultSet))
                .stream()
                .findFirst();
    }

    public List<WorkRequestResponse> findAll(EventStatus status, Priority priority, String budgetCode) {
        StringBuilder sql = new StringBuilder("""
                select task_id, title, priority, budget_code, estimate, status, requested_by,
                       last_actor, reason, last_event_id, last_occurred_at, stream_version
                from work_request_projection
                """);
        List<String> criteria = new ArrayList<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        if (status != null) {
            criteria.add("status = :status");
            parameters.addValue("status", status.name());
        }
        if (priority != null) {
            criteria.add("priority = :priority");
            parameters.addValue("priority", priority.name());
        }
        if (budgetCode != null && !budgetCode.isBlank()) {
            criteria.add("budget_code = :budgetCode");
            parameters.addValue("budgetCode", budgetCode);
        }
        if (!criteria.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", criteria));
        }
        sql.append(" order by last_occurred_at desc, task_id asc");

        return jdbcTemplate.query(sql.toString(), parameters, (resultSet, rowNumber) -> mapRow(resultSet));
    }

    public void deleteAll() {
        jdbcTemplate.getJdbcTemplate().update("delete from work_request_projection");
    }

    public long count() {
        Long count = jdbcTemplate.getJdbcTemplate()
                .queryForObject("select count(*) from work_request_projection", Long.class);
        return count == null ? 0L : count;
    }

    private WorkRequestResponse mapRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new WorkRequestResponse(
                resultSet.getInt("task_id"),
                resultSet.getString("title"),
                Priority.valueOf(resultSet.getString("priority")),
                resultSet.getString("budget_code"),
                resultSet.getInt("estimate"),
                EventStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("requested_by"),
                resultSet.getString("last_actor"),
                resultSet.getString("reason"),
                resultSet.getObject("last_event_id", UUID.class),
                resultSet.getObject("last_occurred_at", OffsetDateTime.class).toInstant(),
                resultSet.getLong("stream_version")
        );
    }
}
