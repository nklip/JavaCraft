package dev.nklip.javacraft.ewrs.api.shared;

import java.time.Instant;

/**
 * Standard error payload returned by EWRS HTTP endpoints.
 * Exists in {@code ewrs-api} so failures are described consistently to callers and reusable in end-to-end assertions.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
