package dev.nklip.javacraft.ewrs.events.impl;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when validation approves a task and forwards it to the worker queue.
 *
 * <p>This class exists so the workflow can distinguish "task created" from "task accepted".
 * A task may be created and then rejected later, so acceptance needs its own event type and status.
 */
public class AcceptedEvent extends BaseEvent {

    public AcceptedEvent(
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
        super(eventId, taskId, priority, title, financeCode, estimate, EventStatus.ACCEPTED,
                occurredAt, actor, correlationId, streamVersion);
    }

}
