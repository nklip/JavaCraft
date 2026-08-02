package dev.nklip.javacraft.ewrs.events.impl;

import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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

    protected final UUID eventId;

    protected final int taskId;

    protected final Priority priority;

    protected final String title;

    protected final String financeCode;

    protected final int estimate;

    protected final EventStatus status;

    protected final Instant occurredAt;

    protected final String actor;

    protected final String correlationId;

    protected final long streamVersion;

    public BaseEvent(
            UUID eventId,
            int taskId,
            Priority priority,
            String title,
            String financeCode,
            int estimate,
            EventStatus status,
            Instant occurredAt,
            String actor,
            String correlationId,
            long streamVersion
    ) {
        this.eventId = Objects.requireNonNull(eventId, "Event id must not be null");
        this.taskId = taskId;
        this.priority = Objects.requireNonNull(priority, "Priority must not be null");
        this.title = Objects.requireNonNull(title, "Title must not be null");
        this.financeCode = Objects.requireNonNull(financeCode, "Finance code must not be null");
        this.estimate = estimate;
        this.status = Objects.requireNonNull(status, "Status must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "Occurred-at must not be null");
        this.actor = Objects.requireNonNull(actor, "Actor must not be null");
        this.correlationId = Objects.requireNonNull(correlationId, "Correlation id must not be null");
        this.streamVersion = streamVersion;
    }

    @Override
    public UUID getEventId() {
        return eventId;
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
    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String getActor() {
        return actor;
    }

    @Override
    public String getCorrelationId() {
        return correlationId;
    }

    @Override
    public long getStreamVersion() {
        return streamVersion;
    }

    @Override
    public boolean equals(Object that) {
        if (that instanceof Event event) {
            return eventId.equals(event.getEventId());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return eventId.hashCode();
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

            int compareTaskId = Integer.compare(this.taskId, that.getTaskId());
            if (compareTaskId != 0) {
                return compareTaskId;
            }

            int compareOccurredAt = this.occurredAt.compareTo(that.getOccurredAt());
            if (compareOccurredAt != 0) {
                return compareOccurredAt;
            }

            return this.eventId.compareTo(that.getEventId());
        } else {
            return -1;
        }
    }

}
