package dev.nklip.javacraft.ses.simulator.service;

import dev.nklip.javacraft.ses.events.Event;
import dev.nklip.javacraft.ses.events.EventNotifier;
import dev.nklip.javacraft.ses.events.EventStatus;
import dev.nklip.javacraft.ses.events.Priority;
import dev.nklip.javacraft.ses.events.impl.AcceptedEvent;
import dev.nklip.javacraft.ses.events.impl.CompletedEvent;
import dev.nklip.javacraft.ses.events.impl.CreatedEvent;
import dev.nklip.javacraft.ses.events.impl.RejectedEvent;
import dev.nklip.javacraft.ses.events.impl.RunningEvent;
import dev.nklip.javacraft.ses.simulator.model.Task;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class EventNotifierWrapperTest {

    @Test
    public void testCreatedEvent() {
        Task task = new Task(Priority.BLOCKER, 1, "Task #1", "finance-1", 5);

        assertPublishedEvent(task, EventStatus.CREATED, CreatedEvent.class, wrapper -> wrapper.createdEvent(task));
    }

    @Test
    public void testAcceptedEvent() {
        Task task = new Task(Priority.CRITICAL, 2, "Task #2", "finance-2", 10);

        assertPublishedEvent(task, EventStatus.ACCEPTED, AcceptedEvent.class, wrapper -> wrapper.acceptedEvent(task));
    }

    @Test
    public void testRejectedEvent() {
        Task task = new Task(Priority.MAJOR, 3, "Task #3", "finance-3", 15);

        assertPublishedEvent(task, EventStatus.REJECTED, RejectedEvent.class, wrapper -> wrapper.rejectedEvent(task));
    }

    @Test
    public void testRunningEvent() {
        Task task = new Task(Priority.NORMAL, 4, "Task #4", "finance-4", 20);

        assertPublishedEvent(task, EventStatus.RUNNING, RunningEvent.class, wrapper -> wrapper.runningEvent(task));
    }

    @Test
    public void testCompletedEvent() {
        Task task = new Task(Priority.MINOR, 5, "Task #5", "finance-5", 25);

        assertPublishedEvent(task, EventStatus.COMPLETED, CompletedEvent.class, wrapper -> wrapper.completedEvent(task));
    }

    private void assertPublishedEvent(Task task, EventStatus status, Class<? extends Event> type, WrapperAction action) {
        EventNotifier eventNotifier = mock(EventNotifier.class);
        EventNotifierWrapper eventNotifierWrapper = new EventNotifierWrapper(eventNotifier);
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        action.publish(eventNotifierWrapper);

        verify(eventNotifier).notify(eventCaptor.capture());

        Event publishedEvent = eventCaptor.getValue();
        Assertions.assertEquals(type, publishedEvent.getClass());
        Assertions.assertEquals(task.getId(), publishedEvent.getTaskId());
        Assertions.assertEquals(task.getPriority(), publishedEvent.getPriority());
        Assertions.assertEquals(task.getTitle(), publishedEvent.getTitle());
        Assertions.assertEquals(task.getFinanceCode(), publishedEvent.getFinanceCode());
        Assertions.assertEquals(task.getEstimate(), publishedEvent.getEstimate());
        Assertions.assertEquals(status, publishedEvent.getStatus());
    }

    @FunctionalInterface
    private interface WrapperAction {

        void publish(EventNotifierWrapper eventNotifierWrapper);
    }
}
