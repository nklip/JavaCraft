package dev.nklip.javacraft.ses.events;

/**
 * Created by nikilipa on 7/25/16.
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

    public static Priority valueOf(int sort) {
        return switch (sort) {
            case 0 -> BLOCKER;
            case 1 -> CRITICAL;
            case 2 -> MAJOR;
            case 3 -> NORMAL;
            case 4 -> MINOR;
            default -> throw new RuntimeException(String.format("Unknown Priority status = %s", sort));
        };
    }

    public int getSort() {
        return sort;
    }
}
