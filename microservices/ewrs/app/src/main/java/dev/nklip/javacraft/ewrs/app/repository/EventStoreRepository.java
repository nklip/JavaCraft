package dev.nklip.javacraft.ewrs.app.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.ewrs.app.model.StoredEventRecord;
import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import dev.nklip.javacraft.ewrs.events.impl.AcceptedEvent;
import dev.nklip.javacraft.ewrs.events.impl.CompletedEvent;
import dev.nklip.javacraft.ewrs.events.impl.CreatedEvent;
import dev.nklip.javacraft.ewrs.events.impl.RejectedEvent;
import dev.nklip.javacraft.ewrs.events.impl.RunningEvent;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns append-only persistence and ordered retrieval for the authoritative {@code event_store}.
 * Architecture mapping: this is the store step of {@code controller -> command -> store -> event_store} and also the
 * history source used by the Read Side timeline and Projection Flow replay/catch-up.
 */
@Repository
public class EventStoreRepository {

    public static final String NOTIFY_CHANNEL = "ewrs_event_store";

    private static final int DEFAULT_BATCH_SIZE = 250;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<StoredEventRecord> storedEventRowMapper = this::mapStoredEvent;

    public EventStoreRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public int nextTaskId() {
        Integer taskId = jdbcTemplate.getJdbcTemplate()
                .queryForObject("select nextval('work_request_id_seq')::int", Integer.class);
        if (taskId == null) {
            throw new IllegalStateException("PostgreSQL did not return the next work request id");
        }
        return taskId;
    }

    @Transactional
    public long append(Event event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eventId", event.getEventId())
                .addValue("taskId", event.getTaskId())
                .addValue("streamVersion", event.getStreamVersion())
                .addValue("eventType", event.getClass().getSimpleName())
                .addValue("status", event.getStatus().name())
                .addValue("payload", toJsonb(writeJson(buildPayload(event))))
                .addValue("metadata", toJsonb(writeJson(buildMetadata(event))))
                .addValue("occurredAt", OffsetDateTime.ofInstant(event.getOccurredAt(), java.time.ZoneOffset.UTC));

        jdbcTemplate.update("""
                insert into event_store (
                    event_id,
                    task_id,
                    stream_version,
                    event_type,
                    status,
                    payload,
                    metadata,
                    occurred_at
                ) values (
                    :eventId,
                    :taskId,
                    :streamVersion,
                    :eventType,
                    :status,
                    :payload,
                    :metadata,
                    :occurredAt
                )
                """, parameters, keyHolder, new String[]{"id"});

        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("PostgreSQL did not return the stored event id");
        }

        notifyProjector(event.getEventId());
        return generatedId.longValue();
    }

    public List<Event> findEventHistory(int taskId) {
        return findStoredEventHistory(taskId).stream()
                .map(StoredEventRecord::event)
                .toList();
    }

    public List<StoredEventRecord> findStoredEventHistory(int taskId) {
        return jdbcTemplate.query("""
                select id, event_id, task_id, stream_version, event_type, status, payload, metadata, occurred_at
                from event_store
                where task_id = :taskId
                order by stream_version asc
                """, Map.of("taskId", taskId), storedEventRowMapper);
    }

    public List<StoredEventRecord> findAfterStoreId(long lastStoreId) {
        return findAfterStoreId(lastStoreId, DEFAULT_BATCH_SIZE);
    }

    public List<StoredEventRecord> findAfterStoreId(long lastStoreId, int batchSize) {
        return jdbcTemplate.query("""
                select id, event_id, task_id, stream_version, event_type, status, payload, metadata, occurred_at
                from event_store
                where id > :lastStoreId
                order by id asc
                limit :batchSize
                """, new MapSqlParameterSource()
                .addValue("lastStoreId", lastStoreId)
                .addValue("batchSize", batchSize), storedEventRowMapper);
    }

    public List<StoredEventRecord> findAllOrdered() {
        return jdbcTemplate.query("""
                select id, event_id, task_id, stream_version, event_type, status, payload, metadata, occurred_at
                from event_store
                order by id asc
                """, storedEventRowMapper);
    }

    private void notifyProjector(UUID eventId) {
        jdbcTemplate.getJdbcTemplate().execute((java.sql.Connection connection) -> {
            try (PreparedStatement statement = connection.prepareStatement("select pg_notify(?, ?)")) {
                statement.setString(1, NOTIFY_CHANNEL);
                statement.setString(2, eventId.toString());
                statement.execute();
            }
            return null;
        });
    }

    private StoredEventRecord mapStoredEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        long storeId = resultSet.getLong("id");
        UUID eventId = UUID.fromString(resultSet.getString("event_id"));
        int taskId = resultSet.getInt("task_id");
        long streamVersion = resultSet.getLong("stream_version");
        Instant occurredAt = resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant();
        JsonNode payload = readJson(resultSet.getString("payload"));
        JsonNode metadata = readJson(resultSet.getString("metadata"));

        Priority priority = Priority.valueOf(requiredText(payload, "priority"));
        String title = requiredText(payload, "title");
        String budgetCode = requiredText(payload, "budgetCode");
        int estimate = payload.path("estimate").asInt();
        String actor = requiredText(metadata, "actor");
        String correlationId = requiredText(metadata, "correlationId");
        String reason = optionalReason(payload);

        Event event = switch (EventStatus.valueOf(resultSet.getString("status"))) {
            case CREATED -> new CreatedEvent(eventId, taskId, priority, title, budgetCode, estimate,
                    occurredAt, actor, correlationId, streamVersion);
            case ACCEPTED -> new AcceptedEvent(eventId, taskId, priority, title, budgetCode, estimate,
                    occurredAt, actor, correlationId, streamVersion);
            case REJECTED -> new RejectedEvent(eventId, taskId, priority, title, budgetCode, estimate,
                    occurredAt, actor, correlationId, streamVersion, reason == null ? "Rejected" : reason);
            case RUNNING -> new RunningEvent(eventId, taskId, priority, title, budgetCode, estimate,
                    occurredAt, actor, correlationId, streamVersion);
            case COMPLETED -> new CompletedEvent(eventId, taskId, priority, title, budgetCode, estimate,
                    occurredAt, actor, correlationId, streamVersion);
        };
        return new StoredEventRecord(storeId, event);
    }

    private Map<String, Object> buildPayload(Event event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", event.getTitle());
        payload.put("priority", event.getPriority().name());
        payload.put("budgetCode", event.getBudgetCode());
        payload.put("estimate", event.getEstimate());
        if (event.getReason() != null) {
            payload.put("reason", event.getReason());
        }
        return payload;
    }

    private Map<String, Object> buildMetadata(Event event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("actor", event.getActor());
        metadata.put("correlationId", event.getCorrelationId());
        metadata.put("streamVersion", event.getStreamVersion());
        return metadata;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to serialize EWRS event payload", e);
        }
    }

    private JsonNode readJson(String rawJson) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to deserialize EWRS event payload", e);
        }
    }

    private PGobject toJsonb(String json) {
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue(json);
            return pgObject;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to convert JSON payload to PostgreSQL jsonb", e);
        }
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Missing required EWRS event field: " + fieldName);
        }
        return value.asString();
    }

    private String optionalReason(JsonNode payload) {
        JsonNode value = payload.get("reason");
        return value == null || value.isNull() ? null : value.asString();
    }
}
