package dev.nklip.javacraft.ewrs.app.exception;

/**
 * Signals that neither the write-side event history nor the read-side projection contains the requested work item.
 * Architecture mapping: used by both Write Side aggregate loading and Read Side query lookups before the controller
 * layer converts it into a {@code 404}.
 */
public class WorkRequestNotFoundException extends RuntimeException {

    public WorkRequestNotFoundException(String message) {
        super(message);
    }
}
