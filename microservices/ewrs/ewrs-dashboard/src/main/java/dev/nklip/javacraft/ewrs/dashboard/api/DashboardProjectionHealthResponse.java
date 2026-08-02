package dev.nklip.javacraft.ewrs.dashboard.api;

/**
 * Read-only projection health snapshot for the dashboard header cards.
 * Exists in {@code ewrs-dashboard} because projection lag is derived from checkpoint and event-store metadata rather
 * than from the public EWRS query DTOs.
 */
public record DashboardProjectionHealthResponse(
        long lastAppliedEventStoreId,
        long latestStoredEventId
) {
}
