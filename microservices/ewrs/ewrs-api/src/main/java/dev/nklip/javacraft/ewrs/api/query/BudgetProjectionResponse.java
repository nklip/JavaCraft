package dev.nklip.javacraft.ewrs.api.query;

/**
 * Read model for current budget usage and remaining capacity.
 * Exists in {@code ewrs-api} because budget projections are consumed both by REST endpoints and projection-focused tests.
 */
public record BudgetProjectionResponse(
        String budgetCode,
        int initialBudget,
        int reservedAmount,
        int remainingBudget
) {
}
