package my.javacraft.elastic.app.config;

public final class SchedulerDefaults {
    private SchedulerDefaults() {
    }

    // we keep 365 days or close to 12 months of data in the index
    public static final int RETENTION_DAYS = 365;
}
