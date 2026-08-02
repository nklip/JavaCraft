package dev.nklip.javacraft.ewrs.app.service;

import dev.nklip.javacraft.ewrs.api.command.ApproveWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CreateWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.RejectWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.StartWorkRequest;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.app.exception.InvalidWorkRequestTransitionException;
import dev.nklip.javacraft.ewrs.app.exception.UnknownBudgetCodeException;
import dev.nklip.javacraft.ewrs.app.repository.EventStoreRepository;
import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import dev.nklip.javacraft.ewrs.events.impl.CreatedEvent;
import dev.nklip.javacraft.ewrs.events.impl.RejectedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkRequestCommandServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-04-18T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void createStoresCreatedEventForKnownBudgetCode() {
        var eventStoreRepository = mock(EventStoreRepository.class);
        var budgetPolicyService = mock(BudgetPolicyService.class);
        when(eventStoreRepository.nextTaskId()).thenReturn(1000);
        when(eventStoreRepository.append(any())).thenReturn(1L);

        WorkRequestCommandService service =
                new WorkRequestCommandService(eventStoreRepository, budgetPolicyService, FIXED_CLOCK);

        WorkRequestResponse response = service.create(new CreateWorkRequest(
                "Replace webhook certificate",
                Priority.CRITICAL,
                "PLATFORM-2026",
                30,
                "Nikita",
                "corr-create"
        ));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventStoreRepository).append(captor.capture());
        Event storedEvent = captor.getValue();

        Assertions.assertAll(
                () -> Assertions.assertInstanceOf(CreatedEvent.class, storedEvent),
                () -> Assertions.assertEquals(1000, storedEvent.getTaskId()),
                () -> Assertions.assertEquals("Nikita", storedEvent.getActor()),
                () -> Assertions.assertEquals("corr-create", storedEvent.getCorrelationId()),
                () -> Assertions.assertEquals(1L, storedEvent.getStreamVersion()),
                () -> Assertions.assertEquals(EventStatus.CREATED, response.status()),
                () -> Assertions.assertEquals("Nikita", response.requestedBy())
        );
    }

    @Test
    void createRejectsUnknownBudgetCode() {
        var eventStoreRepository = mock(EventStoreRepository.class);
        var budgetPolicyService = mock(BudgetPolicyService.class);
        var service = new WorkRequestCommandService(eventStoreRepository, budgetPolicyService, FIXED_CLOCK);

        when(eventStoreRepository.nextTaskId()).thenReturn(1000);
        org.mockito.Mockito.doThrow(new UnknownBudgetCodeException("Unknown budget code: UNKNOWN"))
                .when(budgetPolicyService).assertBudgetCodeExists("UNKNOWN");

        Assertions.assertThrows(UnknownBudgetCodeException.class, () -> service.create(new CreateWorkRequest(
                "Replace webhook certificate",
                Priority.CRITICAL,
                "UNKNOWN",
                30,
                "Nikita",
                "corr-create"
        )));
        verify(eventStoreRepository, never()).append(any());
    }

    @Test
    void approveAppendsAcceptedEventWhenBudgetAllowsIt() {
        var eventStoreRepository = mock(EventStoreRepository.class);
        var budgetPolicyService = mock(BudgetPolicyService.class);
        when(eventStoreRepository.findEventHistory(1001)).thenReturn(List.of(createdEvent(1001, "PLATFORM-2026", 40)));
        when(eventStoreRepository.append(any())).thenReturn(2L);
        when(budgetPolicyService.canReserve("PLATFORM-2026", 40)).thenReturn(true);

        WorkRequestCommandService service =
                new WorkRequestCommandService(eventStoreRepository, budgetPolicyService, FIXED_CLOCK);

        WorkRequestResponse response = service.approve(1001, new ApproveWorkRequest("Lead", "corr-approve"));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventStoreRepository).append(captor.capture());

        Assertions.assertAll(
                () -> Assertions.assertEquals(EventStatus.ACCEPTED, captor.getValue().getStatus()),
                () -> Assertions.assertEquals("Lead", captor.getValue().getActor()),
                () -> Assertions.assertEquals(2L, captor.getValue().getStreamVersion()),
                () -> Assertions.assertEquals(EventStatus.ACCEPTED, response.status()),
                () -> Assertions.assertNull(response.reason())
        );
    }

    @Test
    void approveAppendsRejectedEventWhenBudgetIsInsufficient() {
        var eventStoreRepository = mock(EventStoreRepository.class);
        var budgetPolicyService = mock(BudgetPolicyService.class);
        when(eventStoreRepository.findEventHistory(1002)).thenReturn(List.of(createdEvent(1002, "OPS-2026", 80)));
        when(eventStoreRepository.append(any())).thenReturn(2L);
        when(budgetPolicyService.canReserve("OPS-2026", 80)).thenReturn(false);

        WorkRequestCommandService service =
                new WorkRequestCommandService(eventStoreRepository, budgetPolicyService, FIXED_CLOCK);

        WorkRequestResponse response = service.approve(1002, new ApproveWorkRequest("BudgetBot", "corr-budget"));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventStoreRepository).append(captor.capture());

        Assertions.assertAll(
                () -> Assertions.assertInstanceOf(RejectedEvent.class, captor.getValue()),
                () -> Assertions.assertEquals(EventStatus.REJECTED, captor.getValue().getStatus()),
                () -> Assertions.assertTrue(captor.getValue().getReason().contains("OPS-2026")),
                () -> Assertions.assertEquals(EventStatus.REJECTED, response.status()),
                () -> Assertions.assertTrue(response.reason().contains("OPS-2026"))
        );
    }

    @Test
    void rejectAppendsRejectedEventWithOperatorReason() {
        var eventStoreRepository = mock(EventStoreRepository.class);
        var budgetPolicyService = mock(BudgetPolicyService.class);
        when(eventStoreRepository.findEventHistory(1003)).thenReturn(List.of(createdEvent(1003, "RND-2026", 20)));
        when(eventStoreRepository.append(any())).thenReturn(2L);

        WorkRequestCommandService service =
                new WorkRequestCommandService(eventStoreRepository, budgetPolicyService, FIXED_CLOCK);

        WorkRequestResponse response = service.reject(1003, new RejectWorkRequest(
                "Operator",
                "Security review failed",
                "corr-reject"
        ));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventStoreRepository).append(captor.capture());

        Assertions.assertAll(
                () -> Assertions.assertEquals(EventStatus.REJECTED, captor.getValue().getStatus()),
                () -> Assertions.assertEquals("Security review failed", captor.getValue().getReason()),
                () -> Assertions.assertEquals("Security review failed", response.reason())
        );
    }

    @Test
    void startBeforeApprovalFailsWithoutAppendingEvent() {
        var eventStoreRepository = mock(EventStoreRepository.class);
        var budgetPolicyService = mock(BudgetPolicyService.class);
        when(eventStoreRepository.findEventHistory(1004)).thenReturn(List.of(createdEvent(1004, "RND-2026", 20)));

        WorkRequestCommandService service =
                new WorkRequestCommandService(eventStoreRepository, budgetPolicyService, FIXED_CLOCK);

        Assertions.assertThrows(InvalidWorkRequestTransitionException.class, () ->
                service.start(1004, new StartWorkRequest("Worker", "corr-start"))
        );

        verify(eventStoreRepository, never()).append(any());
        verify(budgetPolicyService, never()).canReserve(anyString(), eq(20));
    }

    private Event createdEvent(int taskId, String budgetCode, int estimate) {
        return new CreatedEvent(
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(taskId)),
                taskId,
                Priority.NORMAL,
                "Task " + taskId,
                budgetCode,
                estimate,
                Instant.parse("2026-04-18T08:00:00Z"),
                "Requester",
                "corr-" + taskId,
                1L
        );
    }
}
