package dev.nklip.javacraft.ewrs.app.service;

import dev.nklip.javacraft.ewrs.api.command.ApproveWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CompleteWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CreateWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.RejectWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.StartWorkRequest;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.app.exception.InvalidWorkRequestTransitionException;
import dev.nklip.javacraft.ewrs.app.exception.WorkRequestNotFoundException;
import dev.nklip.javacraft.ewrs.app.model.WorkRequestAggregate;
import dev.nklip.javacraft.ewrs.app.repository.EventStoreRepository;
import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.impl.AcceptedEvent;
import dev.nklip.javacraft.ewrs.events.impl.CompletedEvent;
import dev.nklip.javacraft.ewrs.events.impl.CreatedEvent;
import dev.nklip.javacraft.ewrs.events.impl.RejectedEvent;
import dev.nklip.javacraft.ewrs.events.impl.RunningEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the command-side workflow rules for creating and transitioning work requests.
 * Architecture mapping: this is the {@code controller -> command} stage from Runtime Topology and the main
 * application service behind the Write Side section.
 */
@Service
public class WorkRequestCommandService {

    private final EventStoreRepository eventStoreRepository;
    private final BudgetPolicyService budgetPolicyService;
    private final Clock clock;

    public WorkRequestCommandService(
            EventStoreRepository eventStoreRepository,
            BudgetPolicyService budgetPolicyService,
            Clock ewrsClock
    ) {
        this.eventStoreRepository = eventStoreRepository;
        this.budgetPolicyService = budgetPolicyService;
        this.clock = ewrsClock;
    }

    @Transactional
    public WorkRequestResponse create(CreateWorkRequest request) {
        budgetPolicyService.assertBudgetCodeExists(request.budgetCode());

        int taskId = eventStoreRepository.nextTaskId();
        CreatedEvent event = new CreatedEvent(
                UUID.randomUUID(),
                taskId,
                request.priority(),
                request.title(),
                request.budgetCode(),
                request.estimate(),
                Instant.now(clock),
                request.requestedBy(),
                correlationId(request.correlationId()),
                1L
        );
        eventStoreRepository.append(event);
        return toResponse(taskId, request.requestedBy(), event);
    }

    @Transactional
    public WorkRequestResponse approve(int requestId, ApproveWorkRequest request) {
        WorkRequestAggregate aggregate = loadAggregate(requestId);
        validate(aggregate.canApprove(),
                "Work request %s cannot be approved from state %s".formatted(requestId, aggregate.status()));

        Event event;
        if (budgetPolicyService.canReserve(aggregate.budgetCode(), aggregate.estimate())) {
            event = new AcceptedEvent(
                    UUID.randomUUID(),
                    requestId,
                    aggregate.priority(),
                    aggregate.title(),
                    aggregate.budgetCode(),
                    aggregate.estimate(),
                    Instant.now(clock),
                    request.actor(),
                    correlationId(request.correlationId()),
                    aggregate.streamVersion() + 1
            );
        } else {
            event = new RejectedEvent(
                    UUID.randomUUID(),
                    requestId,
                    aggregate.priority(),
                    aggregate.title(),
                    aggregate.budgetCode(),
                    aggregate.estimate(),
                    Instant.now(clock),
                    request.actor(),
                    correlationId(request.correlationId()),
                    aggregate.streamVersion() + 1,
                    "Budget code %s does not have enough remaining capacity for estimate %s"
                            .formatted(aggregate.budgetCode(), aggregate.estimate())
            );
        }

        eventStoreRepository.append(event);
        return toResponse(requestId, aggregate.requestedBy(), event);
    }

    @Transactional
    public WorkRequestResponse reject(int requestId, RejectWorkRequest request) {
        WorkRequestAggregate aggregate = loadAggregate(requestId);
        validate(aggregate.canReject(),
                "Work request %s cannot be rejected from state %s".formatted(requestId, aggregate.status()));

        RejectedEvent event = new RejectedEvent(
                UUID.randomUUID(),
                requestId,
                aggregate.priority(),
                aggregate.title(),
                aggregate.budgetCode(),
                aggregate.estimate(),
                Instant.now(clock),
                request.actor(),
                correlationId(request.correlationId()),
                aggregate.streamVersion() + 1,
                request.reason()
        );
        eventStoreRepository.append(event);
        return toResponse(requestId, aggregate.requestedBy(), event);
    }

    @Transactional
    public WorkRequestResponse start(int requestId, StartWorkRequest request) {
        WorkRequestAggregate aggregate = loadAggregate(requestId);
        validate(aggregate.canStart(),
                "Work request %s cannot be started from state %s".formatted(requestId, aggregate.status()));

        RunningEvent event = new RunningEvent(
                UUID.randomUUID(),
                requestId,
                aggregate.priority(),
                aggregate.title(),
                aggregate.budgetCode(),
                aggregate.estimate(),
                Instant.now(clock),
                request.actor(),
                correlationId(request.correlationId()),
                aggregate.streamVersion() + 1
        );
        eventStoreRepository.append(event);
        return toResponse(requestId, aggregate.requestedBy(), event);
    }

    @Transactional
    public WorkRequestResponse complete(int requestId, CompleteWorkRequest request) {
        WorkRequestAggregate aggregate = loadAggregate(requestId);
        validate(aggregate.canComplete(),
                "Work request %s cannot be completed from state %s".formatted(requestId, aggregate.status()));

        CompletedEvent event = new CompletedEvent(
                UUID.randomUUID(),
                requestId,
                aggregate.priority(),
                aggregate.title(),
                aggregate.budgetCode(),
                aggregate.estimate(),
                Instant.now(clock),
                request.actor(),
                correlationId(request.correlationId()),
                aggregate.streamVersion() + 1
        );
        eventStoreRepository.append(event);
        return toResponse(requestId, aggregate.requestedBy(), event);
    }

    private WorkRequestAggregate loadAggregate(int requestId) {
        List<Event> history = eventStoreRepository.findEventHistory(requestId);
        if (history.isEmpty()) {
            throw new WorkRequestNotFoundException("Work request %s does not exist".formatted(requestId));
        }
        return WorkRequestAggregate.rehydrate(requestId, history);
    }

    private void validate(boolean valid, String message) {
        if (!valid) {
            throw new InvalidWorkRequestTransitionException(message);
        }
    }

    private String correlationId(String rawCorrelationId) {
        return rawCorrelationId == null || rawCorrelationId.isBlank()
                ? UUID.randomUUID().toString()
                : rawCorrelationId;
    }

    private WorkRequestResponse toResponse(int taskId, String requestedBy, Event event) {
        return new WorkRequestResponse(
                taskId,
                event.getTitle(),
                event.getPriority(),
                event.getBudgetCode(),
                event.getEstimate(),
                event.getStatus(),
                requestedBy,
                event.getActor(),
                event.getReason(),
                event.getEventId(),
                event.getOccurredAt(),
                event.getStreamVersion()
        );
    }
}
