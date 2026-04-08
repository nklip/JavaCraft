package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.events.Priority;
import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class WorkerTest {

    @Test
    public void testRunSleepsWhenQueueIsEmpty() throws InterruptedException {
        PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
        EventNotifierWrapper eventNotifierWrapper = mock(EventNotifierWrapper.class);

        Worker worker = spy(new Worker(queue, eventNotifierWrapper));
        doThrow(new InterruptedException("Stop worker after empty-queue branch")).when(worker).busyWait(anyLong());

        worker.run();

        verifyNoInteractions(eventNotifierWrapper);
        Assertions.assertTrue(queue.isEmpty());
    }

    @Test
    public void testRunPublishesRunningAndCompletedEventsForTask() throws InterruptedException {
        PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
        EventNotifierWrapper eventNotifierWrapper = mock(EventNotifierWrapper.class);
        Task task = new Task(Priority.BLOCKER, 3, "Task #3", "finance-3", 12);
        queue.add(task);
        AtomicInteger busyWaitCalls = new AtomicInteger();

        Worker worker = spy(new Worker(queue, eventNotifierWrapper));
        doAnswer(invocation -> {
            Long sleep = invocation.getArgument(0, Long.class);
            Assertions.assertTrue(sleep > 0);
            if (busyWaitCalls.incrementAndGet() >= 6) {
                throw new InterruptedException("Stop worker after one processed task");
            }
            return null;
        }).when(worker).busyWait(anyLong());

        worker.run();

        verify(eventNotifierWrapper).runningEvent(task);
        verify(eventNotifierWrapper).completedEvent(task);
        Assertions.assertTrue(queue.isEmpty());
        Assertions.assertTrue(busyWaitCalls.get() >= 2);
    }
}
