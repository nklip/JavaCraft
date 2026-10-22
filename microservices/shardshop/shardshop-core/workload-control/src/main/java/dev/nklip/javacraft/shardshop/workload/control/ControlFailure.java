package dev.nklip.javacraft.shardshop.workload.control;

import java.util.Objects;

/** A rejected control request. The previous control setting stays in effect. */
public final class ControlFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public ControlFailure(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        /** The body is not one JSON object with exactly the documented members. */
        INVALID_REQUEST,
        /** The rate is not a number from zero to the maximum with at most three decimal places. */
        INVALID_RATE,
        /** A seed run is in progress; one run at a time. */
        RUN_ACTIVE
    }
}
