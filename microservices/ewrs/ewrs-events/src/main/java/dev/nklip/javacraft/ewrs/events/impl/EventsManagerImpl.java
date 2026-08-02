package dev.nklip.javacraft.ewrs.events.impl;

import dev.nklip.javacraft.ewrs.events.EventsMonitor;
import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.EventsSubscriptionsManager;
import dev.nklip.javacraft.ewrs.events.EventListener;
import dev.nklip.javacraft.ewrs.events.EventsManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Default Spring-managed implementation of {@link EventsManager}.
 *
 * <p>This class is intentionally thin. Its job is to expose a higher-level subscription API while delegating
 * the actual listener storage to {@link EventsSubscriptionsManager}. That keeps callers such as
 * {@link EventsMonitor} decoupled from the concrete storage structure.
 */
@Service
public class EventsManagerImpl implements EventsManager {

    private final EventsSubscriptionsManager eventsSubscriptionsManager;

    @Autowired
    public EventsManagerImpl(EventsSubscriptionsManager eventsSubscriptionsManager) {
        this.eventsSubscriptionsManager = eventsSubscriptionsManager;
    }

    @Override
    public void subscribe(Class<? extends Event> event, EventListener<Event> listener) {
        eventsSubscriptionsManager.addSubscriber(event, listener);
    }

}
