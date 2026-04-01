package dev.nklip.javacraft.ses.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Created by nikilipa on 7/26/16.
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
                    log.error("Exception occurred when running even [class=%s] listener [class=%s, object=%s]"
                            .formatted(event.getClass(), listener.getClass(), listener),
                            t);
                }
            }
        } catch (Exception e) {
            log.error("Exception when running listeners", e);
        }
    }

}
