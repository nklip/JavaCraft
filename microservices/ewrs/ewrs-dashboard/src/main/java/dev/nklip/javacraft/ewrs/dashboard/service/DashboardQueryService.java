package dev.nklip.javacraft.ewrs.dashboard.service;

import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardOverviewResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardProjectionHealthResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardSummaryResponse;
import dev.nklip.javacraft.ewrs.dashboard.exception.DashboardWorkRequestNotFoundException;
import dev.nklip.javacraft.ewrs.dashboard.repository.DashboardReadRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Shapes raw EWRS read-model queries into one dashboard-oriented contract.
 * Architecture mapping: orchestrates the read-only visualization path between the dashboard controllers and the SQL
 * projections/event history.
 */
@Service
public class DashboardQueryService {

    private static final int RECENT_REQUEST_LIMIT = 8;

    private final DashboardReadRepository dashboardReadRepository;

    public DashboardQueryService(DashboardReadRepository dashboardReadRepository) {
        this.dashboardReadRepository = dashboardReadRepository;
    }

    public DashboardOverviewResponse loadOverview() {
        DashboardReadRepository.DashboardSummarySnapshot summary = dashboardReadRepository.fetchSummary();
        long pendingProjectionEvents = Math.max(
                0L,
                summary.latestStoredEventId() - summary.lastAppliedEventStoreId()
        );

        return new DashboardOverviewResponse(
                new DashboardSummaryResponse(
                        summary.totalRequests(),
                        summary.openRequests(),
                        summary.completedRequests(),
                        summary.rejectedRequests(),
                        summary.storedEvents(),
                        pendingProjectionEvents
                ),
                new DashboardProjectionHealthResponse(
                        summary.lastAppliedEventStoreId(),
                        summary.latestStoredEventId()
                ),
                dashboardReadRepository.findStatusDistribution(),
                dashboardReadRepository.findBudgets(),
                dashboardReadRepository.findEventVolume(),
                dashboardReadRepository.findRecentRequests(RECENT_REQUEST_LIMIT)
        );
    }

    public List<WorkRequestTimelineEventResponse> getTimeline(int requestId) {
        List<WorkRequestTimelineEventResponse> timeline = dashboardReadRepository.findTimeline(requestId);
        if (timeline.isEmpty()) {
            throw new DashboardWorkRequestNotFoundException(
                    "Work request %s does not exist in the event history".formatted(requestId)
            );
        }
        return timeline;
    }
}
