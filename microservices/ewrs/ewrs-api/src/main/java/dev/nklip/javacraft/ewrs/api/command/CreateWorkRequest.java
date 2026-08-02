package dev.nklip.javacraft.ewrs.api.command;

import dev.nklip.javacraft.ewrs.events.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Command payload for creating a new work request.
 * Exists in {@code ewrs-api} so HTTP clients, the Spring app, and test modules share one validated contract.
 */
public record CreateWorkRequest(
        @NotBlank String title,
        @NotNull Priority priority,
        @NotBlank String budgetCode,
        @Positive int estimate,
        @NotBlank String requestedBy,
        String correlationId
) {
}
