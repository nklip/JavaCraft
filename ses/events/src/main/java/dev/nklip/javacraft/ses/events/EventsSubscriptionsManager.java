package dev.nklip.javacraft.ses.events;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by nikilipa on 7/25/16.
 */
@Service
public class EventsSubscriptionsManager {

    private final ConcurrentHashMap<Class<? extends Event>, List<EventListener<Event>>> listeners = new ConcurrentHashMap<>();

    public void addSubscriber(Class<? extends Event> event, EventListener<Event> listener) {
        getListeners(event).add(listener);
    }

    public List<EventListener<Event>> getListeners(Class<? extends Event> event) {
        listeners.putIfAbsent(event, new ArrayList<>());
        return listeners.get(event); // getOrDefault return a default value without inserting it
    }
}
