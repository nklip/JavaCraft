package dev.nklip.javacraft.ses.simulator.service;

import dev.nklip.javacraft.ses.events.impl.*;
import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.events.Event;
import dev.nklip.javacraft.ses.events.EventNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Simulator-facing adapter that translates {@link Task} objects into event objects.
 *
 * <p>The simulator stages work with {@link Task} because that is the natural domain object for creation,
 * validation, and execution. The event subsystem, however, works with immutable event records such as
 * {@link CreatedEvent} and {@link CompletedEvent}. This wrapper keeps that mapping logic in one place instead
 * of spreading event construction across every worker thread.
 *
 * <p>That means the simulator code can simply say:
 * <pre>{@code
 * eventNotifierWrapper.acceptedEvent(task);
 * }</pre>
 * instead of having to know the concrete event class and how it should be populated.
 *
 * <p>Sequence:
 * <pre>{@code
 * Validator.accept(task)
 *     -> EventNotifierWrapper.acceptedEvent(task)
 *     -> new AcceptedEvent(taskId, priority, title, financeCode, estimate)
 *     -> EventNotifier.notify(event)
 * }</pre>
 */
@Service
public class EventNotifierWrapper {

    private final EventNotifier eventNotifier;

    @Autowired
    public EventNotifierWrapper(EventNotifier eventNotifier) {
        this.eventNotifier = eventNotifier;
    }

    public void createdEvent(Task task) {
        notify(new CreatedEvent(task.getId(), task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    public void acceptedEvent(Task task) {
        notify(new AcceptedEvent(task.getId(), task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    public void rejectedEvent(Task task) {
        notify(new RejectedEvent(task.getId(), task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    public void runningEvent(Task task) {
        notify(new RunningEvent(task.getId(), task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    public void completedEvent(Task task) {
        notify(new CompletedEvent(task.getId(), task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    <T extends Event> void notify(T event) {
        eventNotifier.notify(event);
    }

}
