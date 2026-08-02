package dev.nklip.javacraft.ewrs.scenarios.service;

import dev.nklip.javacraft.ewrs.api.command.ApproveWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CompleteWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CreateWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.StartWorkRequest;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.api.shared.ErrorResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.RunScenarioRequest;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioExecutionResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioType;
import dev.nklip.javacraft.ewrs.scenarios.client.ScenarioTargetClient;
import dev.nklip.javacraft.ewrs.scenarios.config.ScenariosProperties;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ScenarioExecutionServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-04-19T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void happyPathRunsAgainstTargetAndReturnsProjectedOutcome() {
        ScenarioTargetClient targetClient = Mockito.mock(ScenarioTargetClient.class);
        ScenariosProperties properties = testProperties();
        ScenarioExecutionService service = new ScenarioExecutionService(targetClient, properties, TEST_CLOCK);

        WorkRequestResponse created = response(1000, EventStatus.CREATED, 1L);
        WorkRequestResponse approved = response(1000, EventStatus.ACCEPTED, 2L);
        WorkRequestResponse started = response(1000, EventStatus.RUNNING, 3L);
        WorkRequestResponse completed = response(1000, EventStatus.COMPLETED, 4L);

        Mockito.when(targetClient.create(Mockito.any(CreateWorkRequest.class))).thenReturn(created);
        Mockito.when(targetClient.approve(Mockito.eq(1000), Mockito.any(ApproveWorkRequest.class))).thenReturn(approved);
        Mockito.when(targetClient.start(Mockito.eq(1000), Mockito.any(StartWorkRequest.class))).thenReturn(started);
        Mockito.when(targetClient.complete(Mockito.eq(1000), Mockito.any(CompleteWorkRequest.class))).thenReturn(completed);
        Mockito.when(targetClient.getRequestIfAvailable(1000)).thenReturn(null, completed);
        Mockito.when(targetClient.getTimeline(1000))
                .thenReturn(List.of(timelineEvent(EventStatus.CREATED)))
                .thenReturn(List.of(
                        timelineEvent(EventStatus.CREATED),
                        timelineEvent(EventStatus.ACCEPTED),
                        timelineEvent(EventStatus.RUNNING),
                        timelineEvent(EventStatus.COMPLETED)
                ));
        Mockito.when(targetClient.targetBaseUrl()).thenReturn("http://localhost:8053");

        ScenarioExecutionResponse response = service.runScenario(ScenarioType.HAPPY_PATH, RunScenarioRequest.defaults());

        Assertions.assertAll(
                () -> Assertions.assertEquals(ScenarioType.HAPPY_PATH, response.scenario()),
                () -> Assertions.assertEquals(1, response.requestedCount()),
                () -> Assertions.assertEquals(Instant.now(TEST_CLOCK), response.executedAt()),
                () -> Assertions.assertEquals("http://localhost:8053", response.targetBaseUrl()),
                () -> Assertions.assertEquals(1, response.runs().size()),
                () -> Assertions.assertEquals(EventStatus.COMPLETED, response.runs().getFirst().projectedStatus()),
                () -> Assertions.assertEquals(
                        List.of(EventStatus.CREATED, EventStatus.ACCEPTED, EventStatus.RUNNING, EventStatus.COMPLETED),
                        response.runs().getFirst().timelineStatuses()
                ),
                () -> Assertions.assertFalse(response.runs().getFirst().expectedConflictObserved())
        );

        ArgumentCaptor<CreateWorkRequest> createCaptor = ArgumentCaptor.forClass(CreateWorkRequest.class);
        Mockito.verify(targetClient).create(createCaptor.capture());
        Assertions.assertAll(
                () -> Assertions.assertEquals("happy path 1", createCaptor.getValue().title()),
                () -> Assertions.assertEquals(Priority.CRITICAL, createCaptor.getValue().priority()),
                () -> Assertions.assertEquals("PLATFORM-2026", createCaptor.getValue().budgetCode()),
                () -> Assertions.assertEquals(40, createCaptor.getValue().estimate()),
                () -> Assertions.assertEquals("ScenarioDriver", createCaptor.getValue().requestedBy())
        );

        Mockito.verify(targetClient).approve(Mockito.eq(1000), Mockito.any(ApproveWorkRequest.class));
        Mockito.verify(targetClient).start(Mockito.eq(1000), Mockito.any(StartWorkRequest.class));
        Mockito.verify(targetClient).complete(Mockito.eq(1000), Mockito.any(CompleteWorkRequest.class));
    }

    @Test
    void invalidStartRecordsConflictAndKeepsCreatedTimeline() {
        ScenarioTargetClient targetClient = Mockito.mock(ScenarioTargetClient.class);
        ScenariosProperties properties = testProperties();
        ScenarioExecutionService service = new ScenarioExecutionService(targetClient, properties, TEST_CLOCK);

        WorkRequestResponse created = response(1001, EventStatus.CREATED, 1L);
        ErrorResponse conflict = new ErrorResponse(
                Instant.now(TEST_CLOCK),
                409,
                "Conflict",
                "Work request 1001 cannot be started from state CREATED",
                "/api/v1/work-requests/1001/start"
        );

        Mockito.when(targetClient.create(Mockito.any(CreateWorkRequest.class))).thenReturn(created);
        Mockito.when(targetClient.startExpectingConflict(Mockito.eq(1001), Mockito.any(StartWorkRequest.class)))
                .thenReturn(conflict);
        Mockito.when(targetClient.getRequestIfAvailable(1001)).thenReturn(created);
        Mockito.when(targetClient.getTimeline(1001)).thenReturn(List.of(timelineEvent(EventStatus.CREATED)));
        Mockito.when(targetClient.targetBaseUrl()).thenReturn("http://localhost:8053");

        ScenarioExecutionResponse response = service.runScenario(ScenarioType.INVALID_START, RunScenarioRequest.defaults());

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, response.runs().size()),
                () -> Assertions.assertEquals(ScenarioType.INVALID_START, response.runs().getFirst().variant()),
                () -> Assertions.assertEquals(EventStatus.CREATED, response.runs().getFirst().projectedStatus()),
                () -> Assertions.assertEquals(List.of(EventStatus.CREATED), response.runs().getFirst().timelineStatuses()),
                () -> Assertions.assertTrue(response.runs().getFirst().expectedConflictObserved())
        );

        Mockito.verify(targetClient).startExpectingConflict(Mockito.eq(1001), Mockito.any(StartWorkRequest.class));
    }

    private static ScenariosProperties testProperties() {
        return new ScenariosProperties(
                "http://localhost:8053",
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                Duration.ofMillis(200),
                Duration.ofMillis(1)
        );
    }

    private static WorkRequestResponse response(int requestId, EventStatus status, long streamVersion) {
        return new WorkRequestResponse(
                requestId,
                "Generated request",
                Priority.NORMAL,
                "PLATFORM-2026",
                10,
                status,
                "ScenarioDriver",
                "Actor",
                null,
                UUID.randomUUID(),
                Instant.parse("2026-04-19T10:15:30Z"),
                streamVersion
        );
    }

    private static WorkRequestTimelineEventResponse timelineEvent(EventStatus status) {
        return new WorkRequestTimelineEventResponse(
                UUID.randomUUID(),
                1000,
                status,
                Priority.NORMAL,
                "Generated request",
                "PLATFORM-2026",
                10,
                "Actor",
                null,
                null,
                Instant.parse("2026-04-19T10:15:30Z"),
                1L
        );
    }
}
