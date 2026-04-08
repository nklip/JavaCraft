package dev.nklip.javacraft.ses.events;

import java.util.List;
import org.springframework.stereotype.Service;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of subscribers grouped by concrete event class.
 *
 * <p>This is the low-level storage used by the event bus. {@link EventNotifier} asks it for every listener
 * registered for the current event class, and {@link dev.nklip.javacraft.ses.events.impl.EventsManagerImpl}
 * uses it to add new subscriptions.
 *
 * <p>The internal structure is intentionally thread-safe because the simulator emits events from multiple
 * worker threads. A {@link ConcurrentHashMap} keeps the per-type listener groups, and each listener list is a
 * {@link CopyOnWriteArrayList} so iteration during notification stays safe even if more listeners are added later.
 */
@Service
public class EventsSubscriptionsManager {

    private final ConcurrentHashMap<Class<? extends Event>, CopyOnWriteArrayList<EventListener<Event>>> listeners = new ConcurrentHashMap<>();

    public void addSubscriber(Class<? extends Event> event, EventListener<Event> listener) {
        getListeners(event).add(listener);
    }

    public List<EventListener<Event>> getListeners(Class<? extends Event> event) {
        return listeners.computeIfAbsent(event, ignored -> new CopyOnWriteArrayList<>());
    }
}
