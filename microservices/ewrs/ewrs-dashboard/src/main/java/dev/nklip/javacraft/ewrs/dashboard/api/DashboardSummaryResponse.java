package dev.nklip.javacraft.ewrs.dashboard.api;

/**
 * Compact KPI set displayed at the top of the dashboard.
 * Exists in {@code ewrs-dashboard} because the UI needs a stable summary contract that is derived from multiple EWRS
 * tables instead of coming from one public API endpoint.
 */
public record DashboardSummaryResponse(
        long totalRequests,
        long openRequests,
        long completedRequests,
        long rejectedRequests,
        long storedEvents,
        long pendingProjectionEvents
) {
}
