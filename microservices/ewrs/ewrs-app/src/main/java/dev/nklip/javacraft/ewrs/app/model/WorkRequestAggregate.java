package dev.nklip.javacraft.ewrs.app.model;

import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.util.List;
import java.util.Objects;

/**
 * In-memory rehydrated view of a work request reconstructed from ordered event history.
 * Architecture mapping: built inside the Write Side so {@code WorkRequestCommandService} can validate transitions
 * before appending the next immutable fact to the event store.
 */
public record WorkRequestAggregate(
        int taskId,
        String title,
        Priority priority,
        String budgetCode,
        int estimate,
        EventStatus status,
        String requestedBy,
        String lastActor,
        String reason,
        long streamVersion,
        boolean exists
) {

    public static WorkRequestAggregate missing(int taskId) {
        return new WorkRequestAggregate(taskId, null, null, null, 0, null, null, null, null, 0L, false);
    }

    public static WorkRequestAggregate rehydrate(int taskId, List<Event> history) {
        Objects.requireNonNull(history, "History must not be null");
        if (history.isEmpty()) {
            return missing(taskId);
        }

        Event createdEvent = history.getFirst();
        Event latestEvent = history.getLast();

        return new WorkRequestAggregate(
                taskId,
                latestEvent.getTitle(),
                latestEvent.getPriority(),
                latestEvent.getBudgetCode(),
                latestEvent.getEstimate(),
                latestEvent.getStatus(),
                createdEvent.getActor(),
                latestEvent.getActor(),
                latestEvent.getReason(),
                latestEvent.getStreamVersion(),
                true
        );
    }

    public boolean canApprove() {
        return exists && status == EventStatus.CREATED;
    }

    public boolean canReject() {
        return exists && status == EventStatus.CREATED;
    }

    public boolean canStart() {
        return exists && status == EventStatus.ACCEPTED;
    }

    public boolean canComplete() {
        return exists && status == EventStatus.RUNNING;
    }
}
