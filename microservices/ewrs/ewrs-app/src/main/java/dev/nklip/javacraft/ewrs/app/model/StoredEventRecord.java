package dev.nklip.javacraft.ewrs.app.model;

import dev.nklip.javacraft.ewrs.events.Event;

/**
 * Internal carrier for one persisted event-store row and its deserialized domain event.
 * Architecture mapping: produced by {@code EventStoreRepository} and consumed by {@code ProjectionCoordinator}
 * during catch-up and replay in the Projection Flow.
 */
public record StoredEventRecord(
        long storeId,
        Event event
) {
}
