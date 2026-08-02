package dev.nklip.javacraft.ewrs.dashboard.api;

import dev.nklip.javacraft.ewrs.events.EventStatus;

/**
 * One status/count slice in the projected work-request distribution.
 * Exists in {@code ewrs-dashboard} because the dashboard turns the current SQL read model into chart-friendly series
 * data for the Read Side visualizations.
 */
public record DashboardStatusPointResponse(
        EventStatus status,
        long requestCount
) {
}
