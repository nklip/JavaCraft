package dev.nklip.javacraft.ses.events;

public interface EventsManager {

    void subscribe(Class<? extends Event> event, EventListener<Event> listener);

}