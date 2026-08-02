package dev.nklip.javacraft.ewrs.events;

import dev.nklip.javacraft.ewrs.events.impl.EventsManagerImpl;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class EventsMonitorTest {

    @Test
    public void testTracksDifferentTasksWithSameTitle() {
        EventsSubscriptionsManager subscriptionsManager = new EventsSubscriptionsManager();
        EventNotifier eventNotifier = new EventNotifier(subscriptionsManager);
        EventsMonitor eventsMonitor = new EventsMonitor(new EventsManagerImpl(subscriptionsManager));

        eventNotifier.notify(TestEvents.createdEvent(UUID.fromString("00000000-0000-0000-0000-000000000601"),
                1, Priority.BLOCKER, "Shared title", "finance-1", 10, "alice", 1));
        eventNotifier.notify(TestEvents.createdEvent(UUID.fromString("00000000-0000-0000-0000-000000000602"),
                2, Priority.CRITICAL, "Shared title", "finance-2", 20, "bob", 1));

        List<Event> events = eventsMonitor.getStorage();

        Assertions.assertEquals(2, events.size());
        Assertions.assertEquals(List.of(1, 2), events.stream().map(Event::getTaskId).toList());
    }

    @Test
    public void testReplacesStoredEventForSameTaskId() {
        EventsSubscriptionsManager subscriptionsManager = new EventsSubscriptionsManager();
        EventNotifier eventNotifier = new EventNotifier(subscriptionsManager);
        EventsMonitor eventsMonitor = new EventsMonitor(new EventsManagerImpl(subscriptionsManager));

        eventNotifier.notify(TestEvents.createdEvent(UUID.fromString("00000000-0000-0000-0000-000000000603"),
                3, Priority.NORMAL, "Task #3", "finance-3", 15, "alice", 1));
        eventNotifier.notify(TestEvents.completedEvent(UUID.fromString("00000000-0000-0000-0000-000000000604"),
                3, Priority.NORMAL, "Task #3", "finance-3", 15, "bob", 2));

        List<Event> events = eventsMonitor.getStorage();

        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(EventStatus.COMPLETED, events.getFirst().getStatus());
        Assertions.assertEquals(3, events.getFirst().getTaskId());
    }

    @Test
    public void testClearRemovesAllTrackedEvents() {
        EventsSubscriptionsManager subscriptionsManager = new EventsSubscriptionsManager();
        EventNotifier eventNotifier = new EventNotifier(subscriptionsManager);
        EventsMonitor eventsMonitor = new EventsMonitor(new EventsManagerImpl(subscriptionsManager));

        eventNotifier.notify(TestEvents.createdEvent(UUID.fromString("00000000-0000-0000-0000-000000000605"),
                5, Priority.MAJOR, "Task #5", "finance-5", 30, "alice", 1));

        Assertions.assertFalse(eventsMonitor.getStorage().isEmpty());

        eventsMonitor.clear();

        Assertions.assertTrue(eventsMonitor.getStorage().isEmpty());
    }
}
