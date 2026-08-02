package dev.nklip.javacraft.ewrs.app.exception;

/**
 * Signals that a command referenced budget metadata that does not exist in the reference data.
 * Architecture mapping: raised from the write-side budget policy check before the command flow can append an event.
 */
public class UnknownBudgetCodeException extends RuntimeException {

    public UnknownBudgetCodeException(String message) {
        super(message);
    }
}
