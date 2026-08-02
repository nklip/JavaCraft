package dev.nklip.javacraft.ewrs.events.impl;

import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.EventListener;
import dev.nklip.javacraft.ewrs.events.EventsSubscriptionsManager;
import dev.nklip.javacraft.ewrs.events.Priority;
import dev.nklip.javacraft.ewrs.events.TestEvents;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

public class EventsManagerImplTest {

    @Test
    public void testSubscribeDelegatesToSubscriptionsManager() {
        EventsSubscriptionsManager eventsSubscriptionsManager = new EventsSubscriptionsManager();
        EventsManagerImpl eventsManager = new EventsManagerImpl(eventsSubscriptionsManager);
        AtomicReference<Event> capturedEvent = new AtomicReference<>();
        EventListener<Event> listener = capturedEvent::set;
        AcceptedEvent acceptedEvent = TestEvents.acceptedEvent(UUID.fromString("00000000-0000-0000-0000-000000000701"),
                11, Priority.CRITICAL, "Task 11", "Finance-11", 25, "alice", 1);

        eventsManager.subscribe(AcceptedEvent.class, listener);

        Assertions.assertEquals(1, eventsSubscriptionsManager.getListeners(AcceptedEvent.class).size());
        eventsSubscriptionsManager.getListeners(AcceptedEvent.class).getFirst().accept(acceptedEvent);
        Assertions.assertSame(acceptedEvent, capturedEvent.get());
    }
}
