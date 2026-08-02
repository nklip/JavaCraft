package dev.nklip.javacraft.ewrs.api.command;

import jakarta.validation.constraints.NotBlank;

/**
 * Command payload for completing a running work request.
 * Exists in {@code ewrs-api} to expose the completion contract without leaking internal command-service types.
 */
public record CompleteWorkRequest(
        @NotBlank String actor,
        String correlationId
) {
}
