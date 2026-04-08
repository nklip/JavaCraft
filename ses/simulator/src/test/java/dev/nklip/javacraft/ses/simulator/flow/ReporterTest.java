package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.events.Event;
import dev.nklip.javacraft.ses.events.EventsMonitor;
import dev.nklip.javacraft.ses.events.Priority;
import dev.nklip.javacraft.ses.events.impl.AcceptedEvent;
import dev.nklip.javacraft.ses.events.impl.CompletedEvent;
import dev.nklip.javacraft.ses.events.impl.CreatedEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReporterTest {

    @Test
    public void testGetSortedEventsForReportDoesNotMutateImmutableMonitorSnapshot() {
        EventsMonitor eventsMonitor = mock(EventsMonitor.class);
        Reporter reporter = new Reporter(eventsMonitor);

        List<Event> immutableSnapshot = List.of(
                new CompletedEvent(3, Priority.NORMAL, "Task #3", "finance-3", 30),
                new AcceptedEvent(2, Priority.CRITICAL, "Task #2", "finance-2", 20),
                new CreatedEvent(1, Priority.BLOCKER, "Task #1", "finance-1", 10)
        );
        when(eventsMonitor.getStorage()).thenReturn(immutableSnapshot);

        List<Event> sortedEvents = reporter.getSortedEventsForReport();

        Assertions.assertEquals(List.of("Task #1", "Task #2", "Task #3"), sortedEvents.stream().map(Event::getTitle).toList());
        Assertions.assertEquals(List.of("Task #3", "Task #2", "Task #1"), immutableSnapshot.stream().map(Event::getTitle).toList());
    }

    @Test
    public void testRunFetchesEventsAfterFirstSleep() throws InterruptedException {
        EventsMonitor eventsMonitor = mock(EventsMonitor.class);
        Reporter reporter = spy(new Reporter(eventsMonitor));
        List<Event> immutableSnapshot = List.of(
                new CompletedEvent(3, Priority.NORMAL, "Task #3", "finance-3", 30),
                new AcceptedEvent(2, Priority.CRITICAL, "Task #2", "finance-2", 20),
                new CreatedEvent(1, Priority.BLOCKER, "Task #1", "finance-1", 10)
        );
        when(eventsMonitor.getStorage()).thenReturn(immutableSnapshot);
        doNothing().doThrow(new InterruptedException("Stop reporter after one iteration")).when(reporter).busyWait();

        reporter.run();

        verify(eventsMonitor).getStorage();
        Assertions.assertEquals(List.of("Task #3", "Task #2", "Task #1"), immutableSnapshot.stream().map(Event::getTitle).toList());
    }
}
