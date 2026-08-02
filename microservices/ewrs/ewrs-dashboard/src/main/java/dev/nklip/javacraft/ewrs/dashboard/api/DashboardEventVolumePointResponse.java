package dev.nklip.javacraft.ewrs.dashboard.api;

/**
 * One chart point for event volume over time.
 * Exists in {@code ewrs-dashboard} because the dashboard aggregates event-store history into chart-ready buckets for
 * ECharts instead of exposing raw SQL rows to the page.
 */
public record DashboardEventVolumePointResponse(
        String day,
        long eventCount
) {
}
