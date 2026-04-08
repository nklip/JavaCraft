package dev.nklip.javacraft.ses.events;

/**
 * Enumerates the lifecycle states that a task can reach in the simulator.
 *
 * <p>This enum exists so the whole module shares one canonical vocabulary for workflow transitions.
 * Every concrete event type maps to exactly one of these statuses, which makes it easy for read-side
 * code such as {@link EventsMonitor} and the simulator reporter to understand what an event means.
 *
 * <p>The {@code sort} value gives the status a stable display order:
 * created -> accepted -> running -> completed -> rejected.
 * That order is used when events are rendered for humans.
 */
public enum EventStatus {
    CREATED("Created", 0),
    ACCEPTED("Accepted", 1),
    RUNNING("In running", 2),
    COMPLETED("Completed", 3),
    REJECTED("Rejected", 4);

    private final String value;
    private final int sort;

    EventStatus(String value, int sort) {
        this.value = value;
        this.sort = sort;
    }

    @Override
    public String toString() {
        return value;
    }

    public int getSort() {
        return sort;
    }
}
