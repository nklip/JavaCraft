package dev.nklip.javacraft.ses.events.impl;

import dev.nklip.javacraft.ses.events.Event;
import dev.nklip.javacraft.ses.events.EventStatus;
import dev.nklip.javacraft.ses.events.Priority;

/**
 * Shared implementation for all concrete workflow events.
 *
 * <p>This base class holds the state that every event variant has in common: which task the event belongs to,
 * what priority and finance code the task has, what the estimate is, and which workflow status this event
 * represents. That keeps the concrete subclasses extremely small; they only need to declare which
 * {@link EventStatus} they stand for.
 *
 * <p>It also centralizes identity and ordering rules:
 * <ul>
 *     <li>events are considered equal when they belong to the same {@code taskId}</li>
 *     <li>events sort first by priority, then by status, then by estimate, then by task id</li>
 * </ul>
 *
 * <p>Those rules are here because they apply to all event variants equally and should not drift between
 * subclasses.
 */
public abstract class BaseEvent implements Event {

    protected final int taskId;

    protected final Priority priority;

    protected final String title;

    protected final String financeCode;

    protected final int estimate;

    protected final EventStatus status;

    public BaseEvent(int taskId, Priority priority, String title, String financeCode, int estimate, EventStatus status) {
        this.taskId = taskId;
        this.priority = priority;
        this.title = title;
        this.financeCode = financeCode;
        this.estimate = estimate;
        this.status = status;
    }

    @Override
    public int getTaskId() {
        return taskId;
    }

    @Override
    public Priority getPriority() {
        return priority;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getFinanceCode() {
        return financeCode;
    }

    @Override
    public int getEstimate() {
        return estimate;
    }

    @Override
    public EventStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object that) {
        if (that instanceof Event event) {
            return taskId == event.getTaskId();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(taskId);
    }

    @Override
    public int compareTo(Event that) {
        if (this.priority.getSort() > that.getPriority().getSort()) {
            return 1;
        } else if (this.priority.getSort() == that.getPriority().getSort()) {
            int compareStatus = that.getStatus().compareTo(this.status);
            if (compareStatus != 0) {
                return compareStatus;
            }

            int compareEstimate = Integer.compare(that.getEstimate(), this.estimate);
            if (compareEstimate != 0) {
                return compareEstimate;
            }

            return Integer.compare(this.taskId, that.getTaskId());
        } else {
            return -1;
        }
    }

}
