package dev.nklip.javacraft.ewrs.events.impl;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when the worker finishes processing a task.
 *
 * <p>This is the terminal success state in the simulator pipeline. The monitor uses it as the latest known
 * state for a task once the execution stage has finished.
 */
public class CompletedEvent extends BaseEvent {

    public CompletedEvent(
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
        super(eventId, taskId, priority, title, financeCode, estimate, EventStatus.COMPLETED,
                occurredAt, actor, correlationId, streamVersion);
    }

}
