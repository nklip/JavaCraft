package dev.nklip.javacraft.ewrs.api.query;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Serializable view of one event in a work request timeline.
 * Exists in {@code ewrs-api} to expose history safely without binding callers to event-store rows or internal event classes.
 */
public record WorkRequestTimelineEventResponse(
        UUID eventId,
        int requestId,
        EventStatus status,
        Priority priority,
        String title,
        String budgetCode,
        int estimate,
        String actor,
        String correlationId,
        String reason,
        Instant occurredAt,
        long streamVersion
) {
}
