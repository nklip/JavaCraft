package dev.nklip.javacraft.ses.events.impl;

import dev.nklip.javacraft.ses.events.EventStatus;
import dev.nklip.javacraft.ses.events.Priority;

/**
 * Event emitted immediately after a new task is created.
 *
 * <p>This is the entry point into the event stream. It records that a task now exists, but it says nothing
 * yet about whether the task can be funded or executed.
 */
public class CreatedEvent extends BaseEvent {

    public CreatedEvent(int taskId, Priority priority, String title, String financeCode, int estimate) {
        super(taskId, priority, title, financeCode, estimate, EventStatus.CREATED);
    }
}
