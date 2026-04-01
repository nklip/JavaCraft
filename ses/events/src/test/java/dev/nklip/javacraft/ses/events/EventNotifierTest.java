package dev.nklip.javacraft.ses.events;

import dev.nklip.javacraft.ses.events.impl.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EventNotifierTest {

    EventsSubscriptionsManager eventsSubscriptionsManager;
    Map<String, Event> storage = new HashMap<>();

    @BeforeEach
    public void setUp() {
        this.eventsSubscriptionsManager = EventsSubscriptionsManagerTest.createEventsSubscriptionsManager(storage);
    }

    @Test
    public void testNotify() {
        EventNotifier eventNotifier = new EventNotifier(this.eventsSubscriptionsManager);

        AcceptedEvent acceptedEvent = new AcceptedEvent(Priority.MAJOR, "Spring #29", "2024-1/4", 5);
        CompletedEvent completedEvent = new CompletedEvent(Priority.BLOCKER, "Spring #27", "2023-4/4", 20);
        CreatedEvent createdEvent = new CreatedEvent(Priority.BLOCKER, "Spring #28-2/2", "2024-1/4", 15);
        RejectedEvent rejectedEvent = new RejectedEvent(Priority.CRITICAL, "Spring #26", "2024-1/4", 40);
        RunningEvent runningEvent = new RunningEvent(Priority.MINOR, "Spring #28-1/2", "2024-1/4", 25);

        Assertions.assertEquals(0, storage.size());

        eventNotifier.notify(acceptedEvent);
        eventNotifier.notify(completedEvent);
        eventNotifier.notify(createdEvent);
        eventNotifier.notify(rejectedEvent);
        eventNotifier.notify(runningEvent);

        Assertions.assertEquals(5, storage.size());
    }
}
