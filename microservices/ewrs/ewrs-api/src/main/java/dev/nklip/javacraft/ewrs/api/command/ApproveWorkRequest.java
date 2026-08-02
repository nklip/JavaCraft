package dev.nklip.javacraft.ewrs.api.command;

import jakarta.validation.constraints.NotBlank;

/**
 * Command payload for approving a work request.
 * Exists in {@code ewrs-api} to keep the approval input stable for controllers, external callers, and integration tests.
 */
public record ApproveWorkRequest(
        @NotBlank String actor,
        String correlationId
) {
}
