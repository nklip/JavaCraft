package dev.nklip.javacraft.ewrs.app.exception;

/**
 * Signals that a command violates the state machine described in the Write Side section of {@code ARCHITECTURE.md}.
 * Architecture mapping: thrown by {@code WorkRequestCommandService} before any invalid event append reaches the store.
 */
public class InvalidWorkRequestTransitionException extends RuntimeException {

    public InvalidWorkRequestTransitionException(String message) {
        super(message);
    }
}
