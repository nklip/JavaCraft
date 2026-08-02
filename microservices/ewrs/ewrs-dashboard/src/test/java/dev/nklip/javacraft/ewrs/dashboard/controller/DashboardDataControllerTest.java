package dev.nklip.javacraft.ewrs.dashboard.controller;

import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardOverviewResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardProjectionHealthResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardSummaryResponse;
import dev.nklip.javacraft.ewrs.dashboard.service.DashboardQueryService;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class DashboardDataControllerTest {

    @Test
    void getOverviewReturnsServicePayload() {
        DashboardQueryService dashboardQueryService = Mockito.mock(DashboardQueryService.class);
        DashboardOverviewResponse overview = new DashboardOverviewResponse(
                new DashboardSummaryResponse(4, 2, 1, 1, 7, 0),
                new DashboardProjectionHealthResponse(7, 7),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        Mockito.when(dashboardQueryService.loadOverview()).thenReturn(overview);

        DashboardDataController controller = new DashboardDataController(dashboardQueryService);

        ResponseEntity<DashboardOverviewResponse> response = controller.getOverview();

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, response.getStatusCode()),
                () -> Assertions.assertSame(overview, response.getBody())
        );
    }

    @Test
    void getTimelineReturnsServicePayload() {
        DashboardQueryService dashboardQueryService = Mockito.mock(DashboardQueryService.class);
        List<WorkRequestTimelineEventResponse> timeline = List.of(new WorkRequestTimelineEventResponse(
                UUID.randomUUID(),
                1000,
                EventStatus.CREATED,
                Priority.CRITICAL,
                "Ship docs",
                "PLATFORM-2026",
                30,
                "Nikita",
                "corr-1",
                null,
                Instant.parse("2026-04-19T11:15:00Z"),
                1
        ));
        Mockito.when(dashboardQueryService.getTimeline(1000)).thenReturn(timeline);

        DashboardDataController controller = new DashboardDataController(dashboardQueryService);

        ResponseEntity<List<WorkRequestTimelineEventResponse>> response = controller.getTimeline(1000);

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, response.getStatusCode()),
                () -> Assertions.assertSame(timeline, response.getBody())
        );
    }
}
