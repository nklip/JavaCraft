package dev.nklip.javacraft.ewrs.dashboard.repository;

import dev.nklip.javacraft.ewrs.api.query.BudgetProjectionResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardEventVolumePointResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardStatusPointResponse;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the EWRS SQL schema in a dashboard-friendly shape.
 * Architecture mapping: explicit JDBC/native-SQL read repository over {@code event_store}, projections, and the
 * projector checkpoint, matching the EWRS Read Side and the JDBC rationale in the architecture document.
 */
@Repository
@SuppressWarnings("unused")
public class DashboardReadRepository {

    private static final String SELECT_SUMMARY = """
            select
                coalesce((select count(*) from work_request_projection), 0) as total_requests,
                coalesce((select count(*)
                          from work_request_projection
                          where status in ('CREATED', 'ACCEPTED', 'RUNNING')), 0) as open_requests,
                coalesce((select count(*)
                          from work_request_projection
                          where status = 'COMPLETED'), 0) as completed_requests,
                coalesce((select count(*)
                          from work_request_projection
                          where status = 'REJECTED'), 0) as rejected_requests,
                coalesce((select count(*) from event_store), 0) as stored_events,
                coalesce((select max(id) from event_store), 0) as latest_stored_event_id,
                coalesce((select last_event_store_id
                          from projection_checkpoint
                          where projection_name = 'ewrs-projector'), 0) as last_applied_event_store_id
            """;

    private static final String SELECT_STATUS_DISTRIBUTION = """
            select status, count(*) as request_count
            from work_request_projection
            group by status
            order by case status
                when 'CREATED' then 1
                when 'ACCEPTED' then 2
                when 'RUNNING' then 3
                when 'COMPLETED' then 4
                when 'REJECTED' then 5
                else 99
            end
            """;

    private static final String SELECT_BUDGETS = """
            select budget_code, initial_budget, reserved_amount, remaining_budget
            from budget_projection
            order by budget_code asc
            """;

    private static final String SELECT_EVENT_VOLUME = """
            select
                to_char(date_trunc('day', occurred_at at time zone 'UTC'), 'YYYY-MM-DD') as event_day,
                count(*) as event_count
            from event_store
            group by date_trunc('day', occurred_at at time zone 'UTC')
            order by date_trunc('day', occurred_at at time zone 'UTC') asc
            """;

    private static final String SELECT_RECENT_REQUESTS = """
            select task_id, title, priority, budget_code, estimate, status, requested_by,
                   last_actor, reason, last_event_id, last_occurred_at, stream_version
            from work_request_projection
            order by last_occurred_at desc, task_id asc
            limit :limit
            """;

    private static final String SELECT_TIMELINE = """
            select
                event_id,
                task_id,
                status,
                payload ->> 'priority' as priority,
                payload ->> 'title' as title,
                payload ->> 'budgetCode' as budget_code,
                cast(payload ->> 'estimate' as integer) as estimate,
                metadata ->> 'actor' as actor,
                metadata ->> 'correlationId' as correlation_id,
                payload ->> 'reason' as reason,
                occurred_at,
                stream_version
            from event_store
            where task_id = :requestId
            order by stream_version asc
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DashboardReadRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DashboardSummarySnapshot fetchSummary() {
        DashboardSummarySnapshot summary = jdbcTemplate.getJdbcTemplate().queryForObject(
                SELECT_SUMMARY,
                (resultSet, rowNumber) -> new DashboardSummarySnapshot(
                        resultSet.getLong("total_requests"),
                        resultSet.getLong("open_requests"),
                        resultSet.getLong("completed_requests"),
                        resultSet.getLong("rejected_requests"),
                        resultSet.getLong("stored_events"),
                        resultSet.getLong("latest_stored_event_id"),
                        resultSet.getLong("last_applied_event_store_id")
                )
        );
        if (summary == null) {
            throw new IllegalStateException("Dashboard summary query returned no result");
        }
        return summary;
    }

    public List<DashboardStatusPointResponse> findStatusDistribution() {
        return jdbcTemplate.getJdbcTemplate().query(SELECT_STATUS_DISTRIBUTION, (resultSet, rowNumber) ->
                new DashboardStatusPointResponse(
                        EventStatus.valueOf(resultSet.getString("status")),
                        resultSet.getLong("request_count")
                ));
    }

    public List<BudgetProjectionResponse> findBudgets() {
        return jdbcTemplate.getJdbcTemplate().query(SELECT_BUDGETS, (resultSet, rowNumber) ->
                new BudgetProjectionResponse(
                        resultSet.getString("budget_code"),
                        resultSet.getInt("initial_budget"),
                        resultSet.getInt("reserved_amount"),
                        resultSet.getInt("remaining_budget")
                ));
    }

    public List<DashboardEventVolumePointResponse> findEventVolume() {
        return jdbcTemplate.getJdbcTemplate().query(SELECT_EVENT_VOLUME, (resultSet, rowNumber) ->
                new DashboardEventVolumePointResponse(
                        resultSet.getString("event_day"),
                        resultSet.getLong("event_count")
                ));
    }

    public List<WorkRequestResponse> findRecentRequests(int limit) {
        return jdbcTemplate.query(SELECT_RECENT_REQUESTS, new MapSqlParameterSource("limit", limit),
                (resultSet, rowNumber) -> new WorkRequestResponse(
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
                ));
    }

    public List<WorkRequestTimelineEventResponse> findTimeline(int requestId) {
        return jdbcTemplate.query(SELECT_TIMELINE, Map.of("requestId", requestId), (resultSet, rowNumber) ->
                new WorkRequestTimelineEventResponse(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getInt("task_id"),
                        EventStatus.valueOf(resultSet.getString("status")),
                        Priority.valueOf(resultSet.getString("priority")),
                        resultSet.getString("title"),
                        resultSet.getString("budget_code"),
                        resultSet.getInt("estimate"),
                        resultSet.getString("actor"),
                        resultSet.getString("correlation_id"),
                        resultSet.getString("reason"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        resultSet.getLong("stream_version")
                ));
    }

    /**
     * Internal summary row assembled straight from the SQL schema before the service shapes it for the UI contract.
     */
    public record DashboardSummarySnapshot(
            long totalRequests,
            long openRequests,
            long completedRequests,
            long rejectedRequests,
            long storedEvents,
            long latestStoredEventId,
            long lastAppliedEventStoreId
    ) {
    }
}
