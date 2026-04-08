package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.events.Priority;
import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;
import dev.nklip.javacraft.ses.simulator.service.FinanceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.PriorityBlockingQueue;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ValidatorTest {

    @Test
    public void testRunAcceptsTaskAndForwardsItToWorkerQueue() throws InterruptedException {
        FinanceService financeService = mock(FinanceService.class);
        EventNotifierWrapper eventNotifierWrapper = mock(EventNotifierWrapper.class);
        PriorityBlockingQueue<Task> fromCreator = new PriorityBlockingQueue<>();
        PriorityBlockingQueue<Task> toWorker = new PriorityBlockingQueue<>();
        Task task = new Task(Priority.CRITICAL, 1, "Task #1", "finance-1", 20);
        fromCreator.add(task);
        when(financeService.isEnoughMoney(task.getFinanceCode(), task.getEstimate())).thenReturn(true);
        when(financeService.updateFinance(task.getFinanceCode(), task.getEstimate())).thenReturn(true);

        Validator validator = spy(new Validator(financeService, eventNotifierWrapper, fromCreator, toWorker));
        doThrow(new InterruptedException("Stop validator after one loop")).when(validator).busyWait(anyLong());

        validator.run();

        verify(financeService).isEnoughMoney(task.getFinanceCode(), task.getEstimate());
        verify(financeService).updateFinance(task.getFinanceCode(), task.getEstimate());
        verify(eventNotifierWrapper).acceptedEvent(task);
        verify(eventNotifierWrapper, never()).rejectedEvent(task);
        Assertions.assertEquals(1, toWorker.size());
        Assertions.assertSame(task, toWorker.peek());
        Assertions.assertTrue(fromCreator.isEmpty());
    }

    @Test
    public void testRunRejectsTaskAndDoesNotForwardIt() throws InterruptedException {
        FinanceService financeService = mock(FinanceService.class);
        EventNotifierWrapper eventNotifierWrapper = mock(EventNotifierWrapper.class);
        PriorityBlockingQueue<Task> fromCreator = new PriorityBlockingQueue<>();
        PriorityBlockingQueue<Task> toWorker = new PriorityBlockingQueue<>();
        Task task = new Task(Priority.MAJOR, 2, "Task #2", "finance-2", 30);
        fromCreator.add(task);
        when(financeService.isEnoughMoney(task.getFinanceCode(), task.getEstimate())).thenReturn(false);

        Validator validator = spy(new Validator(financeService, eventNotifierWrapper, fromCreator, toWorker));
        doThrow(new InterruptedException("Stop validator after one loop")).when(validator).busyWait(anyLong());

        validator.run();

        verify(financeService).isEnoughMoney(task.getFinanceCode(), task.getEstimate());
        verify(financeService, never()).updateFinance(task.getFinanceCode(), task.getEstimate());
        verify(eventNotifierWrapper, never()).acceptedEvent(task);
        verify(eventNotifierWrapper).rejectedEvent(task);
        Assertions.assertTrue(toWorker.isEmpty());
        Assertions.assertTrue(fromCreator.isEmpty());
    }
}
