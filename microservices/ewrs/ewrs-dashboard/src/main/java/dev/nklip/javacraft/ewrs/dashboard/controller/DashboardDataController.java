package dev.nklip.javacraft.ewrs.dashboard.controller;

import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardOverviewResponse;
import dev.nklip.javacraft.ewrs.dashboard.service.DashboardQueryService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves JSON data for the EWRS dashboard charts and drill-down timeline.
 * Architecture mapping: browser-facing read-side controller that queries SQL projections and event history without
 * touching the command flow.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardDataController {

    private final DashboardQueryService dashboardQueryService;

    public DashboardDataController(DashboardQueryService dashboardQueryService) {
        this.dashboardQueryService = dashboardQueryService;
    }

    @GetMapping("/overview")
    public ResponseEntity<DashboardOverviewResponse> getOverview() {
        return ResponseEntity.ok(dashboardQueryService.loadOverview());
    }

    @GetMapping("/work-requests/{requestId}/timeline")
    public ResponseEntity<List<WorkRequestTimelineEventResponse>> getTimeline(
            @PathVariable("requestId") int requestId
    ) {
        return ResponseEntity.ok(dashboardQueryService.getTimeline(requestId));
    }
}
