package dev.nklip.javacraft.ses.events.impl;

import dev.nklip.javacraft.ses.events.Event;
import dev.nklip.javacraft.ses.events.EventStatus;
import dev.nklip.javacraft.ses.events.Priority;

/**
 * Created by nikilipa on 7/25/16.
 */
public abstract class BaseEvent implements Event {

    protected final Priority priority;

    protected final String title;

    protected final String financeCode;

    protected final int estimate;

    protected final EventStatus status;

    protected Exception exception;

    public BaseEvent(Priority priority, String title, String financeCode, int estimate, EventStatus status) {
        this.priority = priority;
        this.title = title;
        this.financeCode = financeCode;
        this.estimate = estimate;
        this.status = status;
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

    public Exception getException() {
        return exception;
    }

    @Override
    public void setException(Exception exception) {
        this.exception = exception;
    }

    @Override
    public boolean equals(Object that) {
        if (that instanceof Event event) {
            return this.title.equals(event.getTitle());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return title.hashCode();
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

            return Integer.compare(that.getEstimate(), this.estimate);
        } else {
            return -1;
        }
    }

}
