package dev.nklip.javacraft.ewrs.api.query;

import java.time.Instant;
import java.util.UUID;

/**
 * SSE payload describing a projection change that subscribers should react to.
 * Exists in {@code ewrs-api} so live updates reuse the same contract across the app, clients, and integration tests.
 */
public record ProjectionUpdateResponse(
        String updateType,
        UUID eventId,
        Instant occurredAt,
        WorkRequestResponse workRequest,
        BudgetProjectionResponse budget
) {
}
