package dev.nklip.javacraft.ses.events;

/**
 * Priority scale shared by tasks and events.
 *
 * <p>The simulator creates tasks with one of these priority levels, and every emitted event carries the same
 * priority so downstream consumers can preserve that context. The {@code sort} value defines the ordering used
 * by task queues and by event comparison logic: lower sort means higher importance.
 */
public enum Priority {
    BLOCKER(0),
    CRITICAL(1),
    MAJOR(2),
    NORMAL(3),
    MINOR(4);

    private final int sort;

    Priority(final int sort) {
        this.sort = sort;
    }

    public int getSort() {
        return sort;
    }
}
