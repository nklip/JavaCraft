package dev.nklip.javacraft.ses.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Central fan-out point for the {@code ses} event system.
 *
 * <p>This service takes one domain event instance and forwards it to every listener that subscribed to
 * that concrete event class through {@link EventsSubscriptionsManager}. The simulator code never updates
 * the monitor directly. Instead, every workflow transition is published here and the interested listeners
 * derive their own view of the world from the stream of events.
 *
 * <p>That separation is important for two reasons:
 * <ol>
 *     <li>the producer side of the simulator only needs to describe "what happened"</li>
 *     <li>consumers such as {@link EventsMonitor} are free to decide how to store or project that state</li>
 * </ol>
 *
 * <p>Sequence:
 * <pre>{@code
 * Creator / Validator / Worker
 *     -> EventNotifierWrapper
 *     -> EventNotifier.notify(event)
 *     -> EventsSubscriptionsManager.getListeners(eventClass)
 *     -> listener.accept(event)
 *     -> EventsMonitor.updateElement(event)
 * }</pre>
 *
 * <p>The implementation deliberately catches listener exceptions one by one so that a broken listener does not
 * prevent other listeners from seeing the same event.
 */
@Service
public class EventNotifier {

    private static final Logger log = LoggerFactory.getLogger(EventNotifier.class);

    private final EventsSubscriptionsManager eventsSubscriptionManager;

    @Autowired
    public EventNotifier(EventsSubscriptionsManager eventsSubscriptionManager) {
        this.eventsSubscriptionManager = eventsSubscriptionManager;
    }

    public <T extends Event> void notify(T event) {
        try {
            for (EventListener<Event> listener : eventsSubscriptionManager.getListeners(event.getClass())) {
                try {
                    listener.accept(event);
                } catch (Throwable t) {
                    log.error("Exception occurred when running even [class={}] listener [class={}, object={}]",
                            event.getClass(),
                            listener.getClass(),
                            listener,
                            t
                    );
                }
            }
        } catch (Exception e) {
            log.error("Exception when running listeners", e);
        }
    }

}
