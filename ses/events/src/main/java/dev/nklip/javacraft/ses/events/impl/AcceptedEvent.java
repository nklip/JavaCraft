package dev.nklip.javacraft.ses.events.impl;

import dev.nklip.javacraft.ses.events.EventStatus;
import dev.nklip.javacraft.ses.events.Priority;

/**
 * Event emitted when validation approves a task and forwards it to the worker queue.
 *
 * <p>This class exists so the workflow can distinguish "task created" from "task accepted".
 * A task may be created and then rejected later, so acceptance needs its own event type and status.
 */
public class AcceptedEvent extends BaseEvent {

    public AcceptedEvent(int taskId, Priority priority, String title, String financeCode, int estimate) {
        super(taskId, priority, title, financeCode, estimate, EventStatus.ACCEPTED);
    }

}
