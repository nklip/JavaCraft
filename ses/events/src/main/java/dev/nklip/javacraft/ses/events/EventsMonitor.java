package dev.nklip.javacraft.ses.events;

import dev.nklip.javacraft.ses.events.impl.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by nikilipa on 7/25/16.
 */
@Service
public class EventsMonitor {

    private static final ConcurrentHashMap<String, Event> storage = new ConcurrentHashMap<>();

    private final EventsManager eventsManager;

    @Autowired
    public EventsMonitor(EventsManager eventsManager) {
        this.eventsManager = eventsManager;

        this.eventsManager.subscribe(AcceptedEvent.class, EventsMonitor::updateElement);
        this.eventsManager.subscribe(CompletedEvent.class, EventsMonitor::updateElement);
        this.eventsManager.subscribe(CreatedEvent.class, EventsMonitor::updateElement);
        this.eventsManager.subscribe(RejectedEvent.class, EventsMonitor::updateElement);
        this.eventsManager.subscribe(RunningEvent.class, EventsMonitor::updateElement);
    }

    protected static void updateElement(Event e) {
        storage.put(e.getTitle(), e);
    }

    public static List<Event> getStorage() {
        List<Event> values = new ArrayList<>(storage.values());
        Collections.sort(values);
        return values;
    }

}
