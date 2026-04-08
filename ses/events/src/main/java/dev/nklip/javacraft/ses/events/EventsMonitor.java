package dev.nklip.javacraft.ses.events;

import dev.nklip.javacraft.ses.events.impl.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * In-memory projection of the latest known event for every task.
 *
 * <p>The monitor is not the component that decides workflow transitions. It only listens to events and
 * stores the newest event by {@code taskId}. That makes it behave like a tiny read model:
 * the simulator publishes facts such as "task created", "task accepted", and "task completed",
 * and the monitor converts those facts into a convenient current-state view for the reporter.
 *
 * <p>Using {@code taskId} as the storage key is intentional. Task titles are human-friendly labels and may
 * collide, while the monitor must still keep different tasks separate. When a later event arrives for the
 * same task id, it replaces the previous entry because it represents the newer state of the same task.
 *
 * <p>Sequence:
 * <pre>{@code
 * EventNotifier.notify(AcceptedEvent / CreatedEvent / RunningEvent / CompletedEvent / RejectedEvent)
 *     -> EventsMonitor.updateElement(event)
 *     -> storage.put(event.taskId, event)
 * Reporter
 *     -> EventsMonitor.getStorage()
 *     -> sorted latest events snapshot
 * }</pre>
 */
@Service
public class EventsMonitor {

    private final Map<Integer, Event> storage = new ConcurrentHashMap<>();

    @Autowired
    public EventsMonitor(EventsManager eventsManager) {

        eventsManager.subscribe(AcceptedEvent.class, this::updateElement);
        eventsManager.subscribe(CompletedEvent.class, this::updateElement);
        eventsManager.subscribe(CreatedEvent.class, this::updateElement);
        eventsManager.subscribe(RejectedEvent.class, this::updateElement);
        eventsManager.subscribe(RunningEvent.class, this::updateElement);
    }

    protected void updateElement(Event event) {
        storage.put(event.getTaskId(), event);
    }

    public List<Event> getStorage() {
        return storage.values().stream()
                .sorted()
                .toList();
    }

}
