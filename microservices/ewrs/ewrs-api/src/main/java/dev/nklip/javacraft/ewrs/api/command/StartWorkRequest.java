package dev.nklip.javacraft.ewrs.api.command;

import jakarta.validation.constraints.NotBlank;

/**
 * Command payload for moving an accepted work request into the running state.
 * Exists in {@code ewrs-api} so the transition input stays decoupled from the application service implementation.
 */
public record StartWorkRequest(
        @NotBlank String actor,
        String correlationId
) {
}
