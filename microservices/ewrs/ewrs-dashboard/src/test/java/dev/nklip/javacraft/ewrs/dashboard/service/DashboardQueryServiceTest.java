package dev.nklip.javacraft.ewrs.dashboard.service;

import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardOverviewResponse;
import dev.nklip.javacraft.ewrs.dashboard.exception.DashboardWorkRequestNotFoundException;
import dev.nklip.javacraft.ewrs.dashboard.repository.DashboardReadRepository;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DashboardQueryServiceTest {

    @Test
    void loadOverviewClampsNegativeProjectionLagToZero() {
        DashboardReadRepository dashboardReadRepository = Mockito.mock(DashboardReadRepository.class);
        Mockito.when(dashboardReadRepository.fetchSummary()).thenReturn(
                new DashboardReadRepository.DashboardSummarySnapshot(4, 2, 1, 1, 6, 3, 8)
        );
        Mockito.when(dashboardReadRepository.findStatusDistribution()).thenReturn(List.of());
        Mockito.when(dashboardReadRepository.findBudgets()).thenReturn(List.of());
        Mockito.when(dashboardReadRepository.findEventVolume()).thenReturn(List.of());
        Mockito.when(dashboardReadRepository.findRecentRequests(8)).thenReturn(List.of());

        DashboardQueryService dashboardQueryService = new DashboardQueryService(dashboardReadRepository);

        DashboardOverviewResponse response = dashboardQueryService.loadOverview();

        Assertions.assertAll(
                () -> Assertions.assertEquals(0, response.summary().pendingProjectionEvents()),
                () -> Assertions.assertEquals(8, response.projectionHealth().lastAppliedEventStoreId()),
                () -> Assertions.assertEquals(3, response.projectionHealth().latestStoredEventId())
        );
    }

    @Test
    void getTimelineReturnsRepositoryHistory() {
        DashboardReadRepository dashboardReadRepository = Mockito.mock(DashboardReadRepository.class);
        List<WorkRequestTimelineEventResponse> timeline = List.of(new WorkRequestTimelineEventResponse(
                UUID.randomUUID(),
                1000,
                EventStatus.CREATED,
                Priority.CRITICAL,
                "Ship dashboard",
                "PLATFORM-2026",
                40,
                "Nikita",
                "corr-1",
                null,
                Instant.parse("2026-04-19T09:00:00Z"),
                1
        ));
        Mockito.when(dashboardReadRepository.findTimeline(1000)).thenReturn(timeline);

        DashboardQueryService dashboardQueryService = new DashboardQueryService(dashboardReadRepository);

        Assertions.assertSame(timeline, dashboardQueryService.getTimeline(1000));
    }

    @Test
    void getTimelineWhenRepositoryReturnsNothingThrowsNotFound() {
        DashboardReadRepository dashboardReadRepository = Mockito.mock(DashboardReadRepository.class);
        Mockito.when(dashboardReadRepository.findTimeline(1000)).thenReturn(List.of());

        DashboardQueryService dashboardQueryService = new DashboardQueryService(dashboardReadRepository);

        Assertions.assertThrows(
                DashboardWorkRequestNotFoundException.class,
                () -> dashboardQueryService.getTimeline(1000)
        );
    }
}
