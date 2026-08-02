package dev.nklip.javacraft.ewrs.dashboard.integration;

import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.dashboard.AbstractDashboardPostgresIntegrationTest;
import dev.nklip.javacraft.ewrs.dashboard.api.DashboardOverviewResponse;
import dev.nklip.javacraft.ewrs.dashboard.exception.DashboardWorkRequestNotFoundException;
import dev.nklip.javacraft.ewrs.dashboard.service.DashboardQueryService;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class DashboardQueryServiceIntegrationTest extends AbstractDashboardPostgresIntegrationTest {

    @Autowired
    private DashboardQueryService dashboardQueryService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void loadOverviewAggregatesCurrentReadModels() {
        Instant createdAt = Instant.parse("2026-04-19T09:00:00Z");
        Instant completedAt = Instant.parse("2026-04-19T10:30:00Z");
        UUID createdEventId = UUID.randomUUID();
        UUID acceptedEventId = UUID.randomUUID();
        UUID completedEventId = UUID.randomUUID();

        insertEvent(
                createdEventId,
                1000,
                1,
                "CreatedEvent",
                "CREATED",
                """
                        {"title":"Ship dashboard","priority":"CRITICAL","budgetCode":"PLATFORM-2026","estimate":40}
                        """,
                """
                        {"actor":"Nikita","correlationId":"corr-1","streamVersion":1}
                        """,
                createdAt
        );
        insertEvent(
                acceptedEventId,
                1000,
                2,
                "AcceptedEvent",
                "ACCEPTED",
                """
                        {"title":"Ship dashboard","priority":"CRITICAL","budgetCode":"PLATFORM-2026","estimate":40}
                        """,
                """
                        {"actor":"Lead","correlationId":"corr-2","streamVersion":2}
                        """,
                Instant.parse("2026-04-19T09:20:00Z")
        );
        insertEvent(
                completedEventId,
                1001,
                2,
                "CompletedEvent",
                "COMPLETED",
                """
                        {"title":"Roll replay docs","priority":"NORMAL","budgetCode":"OPS-2026","estimate":15}
                        """,
                """
                        {"actor":"Dora","correlationId":"corr-3","streamVersion":2}
                """,
                completedAt
        );

        insertWorkRequestProjection(
                1000,
                "Ship dashboard",
                "CRITICAL",
                "PLATFORM-2026",
                40,
                "ACCEPTED",
                "Nikita",
                "Lead",
                null,
                acceptedEventId,
                Instant.parse("2026-04-19T09:20:00Z"),
                2
        );
        insertWorkRequestProjection(
                1001,
                "Roll replay docs",
                "NORMAL",
                "OPS-2026",
                15,
                "COMPLETED",
                "Dora",
                "Dora",
                null,
                completedEventId,
                completedAt,
                2
        );

        updateBudgetProjection("PLATFORM-2026", 40, 210);
        updateBudgetProjection("OPS-2026", 15, 105);
        updateCheckpoint(2);

        DashboardOverviewResponse overview = dashboardQueryService.loadOverview();

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, overview.summary().totalRequests()),
                () -> Assertions.assertEquals(1, overview.summary().openRequests()),
                () -> Assertions.assertEquals(1, overview.summary().completedRequests()),
                () -> Assertions.assertEquals(0, overview.summary().rejectedRequests()),
                () -> Assertions.assertEquals(3, overview.summary().storedEvents()),
                () -> Assertions.assertEquals(1, overview.summary().pendingProjectionEvents()),
                () -> Assertions.assertEquals(2, overview.projectionHealth().lastAppliedEventStoreId()),
                () -> Assertions.assertEquals(3, overview.projectionHealth().latestStoredEventId()),
                () -> Assertions.assertEquals(2, overview.statusDistribution().size()),
                () -> Assertions.assertEquals(3, overview.budgets().size()),
                () -> Assertions.assertEquals(1, overview.eventVolume().size()),
                () -> Assertions.assertEquals(2, overview.recentRequests().size()),
                () -> Assertions.assertEquals(1001, overview.recentRequests().getFirst().requestId())
        );
    }

    @Test
    void getTimelineReturnsOrderedHistory() {
        insertEvent(
                UUID.randomUUID(),
                1000,
                1,
                "CreatedEvent",
                "CREATED",
                """
                        {"title":"Ship dashboard","priority":"CRITICAL","budgetCode":"PLATFORM-2026","estimate":40}
                        """,
                """
                        {"actor":"Nikita","correlationId":"corr-1","streamVersion":1}
                        """,
                Instant.parse("2026-04-19T09:00:00Z")
        );
        insertEvent(
                UUID.randomUUID(),
                1000,
                2,
                "AcceptedEvent",
                "ACCEPTED",
                """
                        {"title":"Ship dashboard","priority":"CRITICAL","budgetCode":"PLATFORM-2026","estimate":40}
                        """,
                """
                        {"actor":"Lead","correlationId":"corr-2","streamVersion":2}
                        """,
                Instant.parse("2026-04-19T09:20:00Z")
        );

        List<WorkRequestTimelineEventResponse> timeline = dashboardQueryService.getTimeline(1000);

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, timeline.size()),
                () -> Assertions.assertEquals(EventStatus.CREATED, timeline.getFirst().status()),
                () -> Assertions.assertEquals(EventStatus.ACCEPTED, timeline.getLast().status()),
                () -> Assertions.assertEquals(1L, timeline.getFirst().streamVersion()),
                () -> Assertions.assertEquals(2L, timeline.getLast().streamVersion())
        );
    }

    @Test
    void getTimelineWhenUnknownRequestThrowsNotFound() {
        Assertions.assertThrows(
                DashboardWorkRequestNotFoundException.class,
                () -> dashboardQueryService.getTimeline(9999)
        );
    }

    @Test
    void dashboardEndpointsRenderOverviewAndPage() {
        insertEvent(
                UUID.randomUUID(),
                1000,
                1,
                "CreatedEvent",
                "CREATED",
                """
                        {"title":"Ship dashboard","priority":"CRITICAL","budgetCode":"PLATFORM-2026","estimate":40}
                        """,
                """
                        {"actor":"Nikita","correlationId":"corr-1","streamVersion":1}
                        """,
                Instant.parse("2026-04-19T09:00:00Z")
        );
        insertWorkRequestProjection(
                1000,
                "Ship dashboard",
                "CRITICAL",
                "PLATFORM-2026",
                40,
                "CREATED",
                "Nikita",
                "Nikita",
                null,
                UUID.randomUUID(),
                Instant.parse("2026-04-19T09:00:00Z"),
                1
        );

        ResponseEntity<String> pageResponse = restTemplate.getForEntity("/", String.class);
        ResponseEntity<String> overviewResponse = restTemplate.getForEntity("/api/v1/dashboard/overview", String.class);

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, pageResponse.getStatusCode()),
                () -> Assertions.assertTrue(pageResponse.getBody() != null
                        && pageResponse.getBody().contains("EWRS Dashboard")),
                () -> Assertions.assertEquals(HttpStatus.OK, overviewResponse.getStatusCode()),
                () -> Assertions.assertTrue(overviewResponse.getBody() != null
                        && overviewResponse.getBody().contains("\"totalRequests\":1"))
        );
    }

    @Test
    void timelineEndpointBindsPathVariableAndReturnsHistory() {
        insertEvent(
                UUID.randomUUID(),
                1000,
                1,
                "CreatedEvent",
                "CREATED",
                """
                        {"title":"Ship dashboard","priority":"CRITICAL","budgetCode":"PLATFORM-2026","estimate":40}
                        """,
                """
                        {"actor":"Nikita","correlationId":"corr-1","streamVersion":1}
                        """,
                Instant.parse("2026-04-19T09:00:00Z")
        );
        insertEvent(
                UUID.randomUUID(),
                1000,
                2,
                "AcceptedEvent",
                "ACCEPTED",
                """
                        {"title":"Ship dashboard","priority":"CRITICAL","budgetCode":"PLATFORM-2026","estimate":40}
                        """,
                """
                        {"actor":"Lead","correlationId":"corr-2","streamVersion":2}
                        """,
                Instant.parse("2026-04-19T09:20:00Z")
        );

        ResponseEntity<String> timelineResponse =
                restTemplate.getForEntity("/api/v1/dashboard/work-requests/1000/timeline", String.class);

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, timelineResponse.getStatusCode()),
                () -> Assertions.assertTrue(timelineResponse.getBody() != null
                        && timelineResponse.getBody().contains("\"requestId\":1000")),
                () -> Assertions.assertTrue(timelineResponse.getBody() != null
                        && timelineResponse.getBody().contains("\"status\":\"CREATED\"")),
                () -> Assertions.assertTrue(timelineResponse.getBody() != null
                        && timelineResponse.getBody().contains("\"status\":\"ACCEPTED\""))
        );
    }
}
