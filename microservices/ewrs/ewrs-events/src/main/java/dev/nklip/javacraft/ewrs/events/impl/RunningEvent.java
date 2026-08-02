package dev.nklip.javacraft.ewrs.events.impl;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when a worker starts executing an accepted task.
 *
 * <p>This separates queue acceptance from actual execution. A task can be accepted by validation and still
 * wait in the worker queue for some time, so the running state deserves its own event type.
 */
public class RunningEvent extends BaseEvent {

    public RunningEvent(
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
        super(eventId, taskId, priority, title, financeCode, estimate, EventStatus.RUNNING,
                occurredAt, actor, correlationId, streamVersion);
    }
}
