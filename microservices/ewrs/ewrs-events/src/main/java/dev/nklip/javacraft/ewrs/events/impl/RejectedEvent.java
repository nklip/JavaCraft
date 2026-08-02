package dev.nklip.javacraft.ewrs.events.impl;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Event emitted when validation rejects a task because it cannot proceed.
 *
 * <p>In the current simulator that usually means the chosen finance code does not have enough remaining
 * capacity. This type exists so rejected tasks are still visible in the event stream and in the monitor,
 * even though they never reach the worker queue.
 */
public class RejectedEvent extends BaseEvent {

    private final String reason;

    public RejectedEvent(
            UUID eventId,
            int taskId,
            Priority priority,
            String title,
            String financeCode,
            int estimate,
            Instant occurredAt,
            String actor,
            String correlationId,
            long streamVersion,
            String reason
    ) {
        super(eventId, taskId, priority, title, financeCode, estimate, EventStatus.REJECTED,
                occurredAt, actor, correlationId, streamVersion);
        this.reason = Objects.requireNonNull(reason, "Reason must not be null");
    }

    @Override
    public String getReason() {
        return reason;
    }
}
