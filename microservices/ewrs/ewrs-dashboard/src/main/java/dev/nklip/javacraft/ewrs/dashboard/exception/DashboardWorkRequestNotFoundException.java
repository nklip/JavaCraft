package dev.nklip.javacraft.ewrs.dashboard.exception;

/**
 * Signals that the dashboard drill-down requested a work request that has no event history.
 * Architecture mapping: raised on the read side when a timeline lookup cannot be resolved from {@code event_store}.
 */
public class DashboardWorkRequestNotFoundException extends RuntimeException {

    public DashboardWorkRequestNotFoundException(String message) {
        super(message);
    }
}
