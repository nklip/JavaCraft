package dev.nklip.javacraft.ewrs.scenarios.exception;

/**
 * Signals that a named scenario could not be executed to completion against the target EWRS service.
 * It keeps scenario-driver failures explicit instead of leaking low-level HTTP client exceptions to callers.
 */
public class ScenarioExecutionException extends RuntimeException {

    public ScenarioExecutionException(String message) {
        super(message);
    }

    public ScenarioExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
