package dev.nklip.javacraft.ses.events;

import dev.nklip.javacraft.ses.events.impl.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class EventsSubscriptionsManagerTest {

    @Test
    public void testGetListeners() {
        Map<Integer, Event> storage = new HashMap<>();
        EventsSubscriptionsManager manager = createEventsSubscriptionsManager(storage);

        Assertions.assertEquals(1, manager.getListeners(AcceptedEvent.class).size());
        Assertions.assertEquals(1, manager.getListeners(CompletedEvent.class).size());
        Assertions.assertTrue(storage.isEmpty());
    }

    public static EventsSubscriptionsManager createEventsSubscriptionsManager(Map<Integer, Event> storage) {
        EventsSubscriptionsManager manager = new EventsSubscriptionsManager();
        Assertions.assertEquals(0, manager.getListeners(AcceptedEvent.class).size());

        EventListener<Event> eventListener = e -> storage.put(e.getTaskId(), e);
        manager.addSubscriber(AcceptedEvent.class, eventListener);
        manager.addSubscriber(CompletedEvent.class, eventListener);
        manager.addSubscriber(CreatedEvent.class, eventListener);
        manager.addSubscriber(RejectedEvent.class, eventListener);
        manager.addSubscriber(RunningEvent.class, eventListener);

        return manager;
    }
}
