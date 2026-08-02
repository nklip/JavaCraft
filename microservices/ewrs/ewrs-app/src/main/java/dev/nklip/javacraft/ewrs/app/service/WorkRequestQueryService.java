package dev.nklip.javacraft.ewrs.app.service;

import dev.nklip.javacraft.ewrs.api.query.BudgetProjectionResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.app.exception.WorkRequestNotFoundException;
import dev.nklip.javacraft.ewrs.app.repository.BudgetProjectionRepository;
import dev.nklip.javacraft.ewrs.app.repository.WorkRequestProjectionRepository;
import dev.nklip.javacraft.ewrs.app.repository.EventStoreRepository;
import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Serves read-side queries over projections and event history.
 * Architecture mapping: used by the controller layer to read the current request projection, budget projection,
 * and direct timeline history described in the Read Side section.
 */
@Service
public class WorkRequestQueryService {

    private final WorkRequestProjectionRepository workRequestProjectionRepository;
    private final BudgetProjectionRepository budgetProjectionRepository;
    private final EventStoreRepository eventStoreRepository;

    public WorkRequestQueryService(
            WorkRequestProjectionRepository workRequestProjectionRepository,
            BudgetProjectionRepository budgetProjectionRepository,
            EventStoreRepository eventStoreRepository
    ) {
        this.workRequestProjectionRepository = workRequestProjectionRepository;
        this.budgetProjectionRepository = budgetProjectionRepository;
        this.eventStoreRepository = eventStoreRepository;
    }

    public WorkRequestResponse getRequest(int requestId) {
        return workRequestProjectionRepository.findByRequestId(requestId)
                .orElseThrow(() -> new WorkRequestNotFoundException(
                        "Projected work request %s does not exist yet".formatted(requestId)
                ));
    }

    public List<WorkRequestResponse> listRequests(EventStatus status, Priority priority, String budgetCode) {
        return workRequestProjectionRepository.findAll(status, priority, budgetCode);
    }

    public List<WorkRequestTimelineEventResponse> getTimeline(int requestId) {
        List<Event> history = eventStoreRepository.findEventHistory(requestId);
        if (history.isEmpty()) {
            throw new WorkRequestNotFoundException("Work request %s does not exist".formatted(requestId));
        }

        return history.stream()
                .map(event -> new WorkRequestTimelineEventResponse(
                        event.getEventId(),
                        event.getTaskId(),
                        event.getStatus(),
                        event.getPriority(),
                        event.getTitle(),
                        event.getBudgetCode(),
                        event.getEstimate(),
                        event.getActor(),
                        event.getCorrelationId(),
                        event.getReason(),
                        event.getOccurredAt(),
                        event.getStreamVersion()
                ))
                .toList();
    }

    public List<BudgetProjectionResponse> getBudgets() {
        return budgetProjectionRepository.findAll();
    }
}
