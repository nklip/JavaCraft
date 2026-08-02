package dev.nklip.javacraft.ewrs.events.impl;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted immediately after a new task is created.
 *
 * <p>This is the entry point into the event stream. It records that a task now exists, but it says nothing
 * yet about whether the task can be funded or executed.
 */
public class CreatedEvent extends BaseEvent {

    public CreatedEvent(
            UUID eventId,
            int taskId,
            Priority priority,
            String title,
            String financeCode,
            int estimate,
            Instant occurredAt,
            String actor,
            String correlationId,
            long streamVersion
    ) {
        super(eventId, taskId, priority, title, financeCode, estimate, EventStatus.CREATED,
                occurredAt, actor, correlationId, streamVersion);
    }
}
