package dev.nklip.javacraft.ses.events;

import dev.nklip.javacraft.ses.events.impl.CompletedEvent;
import dev.nklip.javacraft.ses.events.impl.CreatedEvent;
import dev.nklip.javacraft.ses.events.impl.EventsManagerImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class EventsMonitorTest {

    @Test
    public void testTracksDifferentTasksWithSameTitle() {
        EventsSubscriptionsManager subscriptionsManager = new EventsSubscriptionsManager();
        EventNotifier eventNotifier = new EventNotifier(subscriptionsManager);
        EventsMonitor eventsMonitor = new EventsMonitor(new EventsManagerImpl(subscriptionsManager));

        eventNotifier.notify(new CreatedEvent(1, Priority.BLOCKER, "Shared title", "finance-1", 10));
        eventNotifier.notify(new CreatedEvent(2, Priority.CRITICAL, "Shared title", "finance-2", 20));

        List<Event> events = eventsMonitor.getStorage();

        Assertions.assertEquals(2, events.size());
        Assertions.assertEquals(List.of(1, 2), events.stream().map(Event::getTaskId).toList());
    }

    @Test
    public void testReplacesStoredEventForSameTaskId() {
        EventsSubscriptionsManager subscriptionsManager = new EventsSubscriptionsManager();
        EventNotifier eventNotifier = new EventNotifier(subscriptionsManager);
        EventsMonitor eventsMonitor = new EventsMonitor(new EventsManagerImpl(subscriptionsManager));

        eventNotifier.notify(new CreatedEvent(3, Priority.NORMAL, "Task #3", "finance-3", 15));
        eventNotifier.notify(new CompletedEvent(3, Priority.NORMAL, "Task #3", "finance-3", 15));

        List<Event> events = eventsMonitor.getStorage();

        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(EventStatus.COMPLETED, events.getFirst().getStatus());
        Assertions.assertEquals(3, events.getFirst().getTaskId());
    }
}
