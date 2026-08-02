package dev.nklip.javacraft.ewrs.api.query;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Projected snapshot of a work request returned by command and query endpoints.
 * Exists in {@code ewrs-api} so read models can be shared by REST controllers, SSE payloads, and black-box tests.
 */
public record WorkRequestResponse(
        int requestId,
        String title,
        Priority priority,
        String budgetCode,
        int estimate,
        EventStatus status,
        String requestedBy,
        String lastActor,
        String reason,
        UUID lastEventId,
        Instant lastOccurredAt,
        long streamVersion
) {
}
