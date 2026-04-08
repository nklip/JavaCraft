package dev.nklip.javacraft.ses.events.impl;

import dev.nklip.javacraft.ses.events.EventStatus;
import dev.nklip.javacraft.ses.events.Priority;

/**
 * Event emitted when validation rejects a task because it cannot proceed.
 *
 * <p>In the current simulator that usually means the chosen finance code does not have enough remaining
 * capacity. This type exists so rejected tasks are still visible in the event stream and in the monitor,
 * even though they never reach the worker queue.
 */
public class RejectedEvent extends BaseEvent {

    public RejectedEvent(int taskId, Priority priority, String title, String financeCode, int estimate) {
        super(taskId, priority, title, financeCode, estimate, EventStatus.REJECTED);
    }
}
