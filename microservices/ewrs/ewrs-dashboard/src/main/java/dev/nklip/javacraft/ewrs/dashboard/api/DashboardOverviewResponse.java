package dev.nklip.javacraft.ewrs.dashboard.api;

import dev.nklip.javacraft.ewrs.api.query.BudgetProjectionResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import java.util.List;

/**
 * Aggregated payload that powers the EWRS dashboard landing page.
 * Exists in {@code ewrs-dashboard} so the browser can hydrate charts, summary cards, and recent-request panels with a
 * single read-side call.
 */
public record DashboardOverviewResponse(
        DashboardSummaryResponse summary,
        DashboardProjectionHealthResponse projectionHealth,
        List<DashboardStatusPointResponse> statusDistribution,
        List<BudgetProjectionResponse> budgets,
        List<DashboardEventVolumePointResponse> eventVolume,
        List<WorkRequestResponse> recentRequests
) {
}
