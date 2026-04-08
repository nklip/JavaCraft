package dev.nklip.javacraft.ses.events;

import dev.nklip.javacraft.ses.events.impl.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EventNotifierTest {

    EventsSubscriptionsManager eventsSubscriptionsManager;
    Map<Integer, Event> storage = new HashMap<>();

    @BeforeEach
    public void setUp() {
        this.eventsSubscriptionsManager = EventsSubscriptionsManagerTest.createEventsSubscriptionsManager(storage);
    }

    @Test
    public void testNotify() {
        EventNotifier eventNotifier = new EventNotifier(this.eventsSubscriptionsManager);

        AcceptedEvent acceptedEvent = new AcceptedEvent(29, Priority.MAJOR, "Spring #29", "2024-1/4", 5);
        CompletedEvent completedEvent = new CompletedEvent(27, Priority.BLOCKER, "Spring #27", "2023-4/4", 20);
        CreatedEvent createdEvent = new CreatedEvent(282, Priority.BLOCKER, "Spring #28-2/2", "2024-1/4", 15);
        RejectedEvent rejectedEvent = new RejectedEvent(26, Priority.CRITICAL, "Spring #26", "2024-1/4", 40);
        RunningEvent runningEvent = new RunningEvent(281, Priority.MINOR, "Spring #28-1/2", "2024-1/4", 25);

        Assertions.assertEquals(0, storage.size());

        eventNotifier.notify(acceptedEvent);
        eventNotifier.notify(completedEvent);
        eventNotifier.notify(createdEvent);
        eventNotifier.notify(rejectedEvent);
        eventNotifier.notify(runningEvent);

        Assertions.assertEquals(5, storage.size());
        Assertions.assertSame(acceptedEvent, storage.get(29));
        Assertions.assertSame(completedEvent, storage.get(27));
        Assertions.assertSame(createdEvent, storage.get(282));
        Assertions.assertSame(rejectedEvent, storage.get(26));
        Assertions.assertSame(runningEvent, storage.get(281));
    }

    @Test
    public void testNotifyContinuesWhenOneListenerFails() {
        EventNotifier eventNotifier = new EventNotifier(this.eventsSubscriptionsManager);
        AcceptedEvent acceptedEvent = new AcceptedEvent(29, Priority.MAJOR, "Spring #29", "2024-1/4", 5);

        EventListener<Event> failingListener = event -> {
            throw new IllegalStateException("Expected test exception");
        };
        eventsSubscriptionsManager.addSubscriber(AcceptedEvent.class, failingListener);

        Assertions.assertDoesNotThrow(() -> eventNotifier.notify(acceptedEvent));
        Assertions.assertSame(acceptedEvent, storage.get(29));
    }

    @Test
    public void testNotifySwallowsListenerLookupFailure() {
        EventsSubscriptionsManager brokenManager = new EventsSubscriptionsManager() {
            @Override
            public java.util.List<EventListener<Event>> getListeners(Class<? extends Event> event) {
                throw new IllegalStateException("Expected test exception");
            }
        };
        EventNotifier eventNotifier = new EventNotifier(brokenManager);
        AcceptedEvent acceptedEvent = new AcceptedEvent(29, Priority.MAJOR, "Spring #29", "2024-1/4", 5);

        Assertions.assertDoesNotThrow(() -> eventNotifier.notify(acceptedEvent));
    }
}
