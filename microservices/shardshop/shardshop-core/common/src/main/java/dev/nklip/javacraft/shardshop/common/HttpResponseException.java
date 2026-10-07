package dev.nklip.javacraft.shardshop.common;

import java.io.IOException;
import java.util.Optional;

/** A rejected HTTP status with an optional bounded error-code token, never a response body. */
public final class HttpResponseException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String errorCode;

    HttpResponseException(int statusCode, String errorCode) {
        super("HTTP response rejected (status " + statusCode + ")");
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Empty when the error body is absent, malformed, or has no valid uppercase code token. */
    public Optional<String> errorCode() {
        return Optional.ofNullable(errorCode);
    }
}
