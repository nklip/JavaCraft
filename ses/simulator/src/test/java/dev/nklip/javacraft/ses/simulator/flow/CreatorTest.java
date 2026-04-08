package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.events.Priority;
import dev.nklip.javacraft.ses.simulator.db.FinanceDao;
import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.random.RandomGenerator;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CreatorTest {

    @Test
    public void testCreateTaskUsesConfiguredPriorityAndFinanceCodeBounds() {
        PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
        EventNotifierWrapper eventNotifierWrapper = mock(EventNotifierWrapper.class);
        Creator creator = new Creator(queue, eventNotifierWrapper);
        RandomGenerator randomGenerator = mock(RandomGenerator.class);

        when(randomGenerator.nextInt(Priority.values().length)).thenReturn(Priority.MINOR.ordinal());
        when(randomGenerator.nextInt(FinanceDao.getSupportedFinanceCodes().size()))
                .thenReturn(FinanceDao.getSupportedFinanceCodes().indexOf(FinanceDao.FINANCE_CODE_MIGRATION));
        when(randomGenerator.nextInt(2, 40)).thenReturn(39);

        Task task = creator.createTask(randomGenerator);

        Assertions.assertEquals(Priority.MINOR, task.getPriority());
        Assertions.assertEquals(FinanceDao.FINANCE_CODE_MIGRATION, task.getFinanceCode());
        Assertions.assertEquals(39, task.getEstimate());
        Assertions.assertEquals(0, task.getId());
        Assertions.assertEquals("Task #0", task.getTitle());
    }

    @Test
    public void testCreateTaskIncrementsIds() {
        PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
        EventNotifierWrapper eventNotifierWrapper = mock(EventNotifierWrapper.class);
        Creator creator = new Creator(queue, eventNotifierWrapper);
        RandomGenerator randomGenerator = mock(RandomGenerator.class);

        when(randomGenerator.nextInt(Priority.values().length)).thenReturn(Priority.BLOCKER.ordinal(), Priority.BLOCKER.ordinal());
        when(randomGenerator.nextInt(FinanceDao.getSupportedFinanceCodes().size())).thenReturn(0, 0);
        when(randomGenerator.nextInt(2, 40)).thenReturn(10, 11);

        Task firstTask = creator.createTask(randomGenerator);
        Task secondTask = creator.createTask(randomGenerator);

        Assertions.assertEquals(0, firstTask.getId());
        Assertions.assertEquals(1, secondTask.getId());
        Assertions.assertEquals("Task #0", firstTask.getTitle());
        Assertions.assertEquals("Task #1", secondTask.getTitle());
    }

    @Test
    public void testRunCreatesTaskPublishesCreatedEventAndQueuesIt() throws InterruptedException {
        PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
        EventNotifierWrapper eventNotifierWrapper = mock(EventNotifierWrapper.class);
        Creator creator = spy(new Creator(queue, eventNotifierWrapper));
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);

        doThrow(new InterruptedException("Stop creator after one iteration")).when(creator).busyWait(anyLong());

        creator.run();

        verify(eventNotifierWrapper).createdEvent(taskCaptor.capture());

        Task createdTask = taskCaptor.getValue();
        Assertions.assertAll(
                () -> Assertions.assertEquals(1, queue.size()),
                () -> Assertions.assertSame(createdTask, queue.peek()),
                () -> Assertions.assertEquals(0, createdTask.getId()),
                () -> Assertions.assertEquals("Task #0", createdTask.getTitle()),
                () -> Assertions.assertTrue(FinanceDao.getSupportedFinanceCodes().contains(createdTask.getFinanceCode())),
                () -> Assertions.assertTrue(createdTask.getEstimate() >= 2),
                () -> Assertions.assertTrue(createdTask.getEstimate() < 40)
        );
    }
}
