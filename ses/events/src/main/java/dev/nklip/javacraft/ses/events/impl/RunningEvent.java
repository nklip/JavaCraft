package dev.nklip.javacraft.ses.events.impl;

import dev.nklip.javacraft.ses.events.EventStatus;
import dev.nklip.javacraft.ses.events.Priority;

/**
 * Event emitted when a worker starts executing an accepted task.
 *
 * <p>This separates queue acceptance from actual execution. A task can be accepted by validation and still
 * wait in the worker queue for some time, so the running state deserves its own event type.
 */
public class RunningEvent extends BaseEvent {

    public RunningEvent(int taskId, Priority priority, String title, String financeCode, int estimate) {
        super(taskId, priority, title, financeCode, estimate, EventStatus.RUNNING);
    }
}
