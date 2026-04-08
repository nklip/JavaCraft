package dev.nklip.javacraft.ses.events;

/**
 * Small abstraction for registering listeners against event types.
 *
 * <p>This interface exists so consumers such as {@link EventsMonitor} do not need to know the storage details
 * of {@link EventsSubscriptionsManager}. They only need one capability: subscribe a listener to a concrete
 * event class. The implementation behind that contract can then decide how subscribers are stored.
 */
public interface EventsManager {

    void subscribe(Class<? extends Event> event, EventListener<Event> listener);

}
