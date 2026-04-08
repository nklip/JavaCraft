package dev.nklip.javacraft.ses.events.impl;

import dev.nklip.javacraft.ses.events.EventStatus;
import dev.nklip.javacraft.ses.events.Priority;

/**
 * Event emitted when the worker finishes processing a task.
 *
 * <p>This is the terminal success state in the simulator pipeline. The monitor uses it as the latest known
 * state for a task once the execution stage has finished.
 */
public class CompletedEvent extends BaseEvent {

    public CompletedEvent(int taskId, Priority priority, String title, String financeCode, int estimate) {
        super(taskId, priority, title, financeCode, estimate, EventStatus.COMPLETED);
    }

}
