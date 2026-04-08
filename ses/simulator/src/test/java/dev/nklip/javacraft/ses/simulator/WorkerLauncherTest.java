package dev.nklip.javacraft.ses.simulator;

import dev.nklip.javacraft.ses.events.EventsMonitor;
import dev.nklip.javacraft.ses.simulator.flow.Creator;
import dev.nklip.javacraft.ses.simulator.flow.Reporter;
import dev.nklip.javacraft.ses.simulator.flow.Validator;
import dev.nklip.javacraft.ses.simulator.flow.Worker;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;
import dev.nklip.javacraft.ses.simulator.service.FinanceService;
import dev.nklip.javacraft.ses.simulator.service.QueueService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class WorkerLauncherTest {

    @Test
    public void testLaunchSchedulesWorkersOnlyOnce() {
        FinanceService financeService = mock(FinanceService.class);
        QueueService queueService = mock(QueueService.class);
        EventNotifierWrapper eventNotifierWrapper = mock(EventNotifierWrapper.class);
        EventsMonitor eventsMonitor = mock(EventsMonitor.class);
        ExecutorService executorService = mock(ExecutorService.class);

        when(queueService.getCreationQueue()).thenReturn(new PriorityBlockingQueue<>());
        when(queueService.getValidationQueue()).thenReturn(new PriorityBlockingQueue<>());

        WorkerLauncher workerLauncher = new WorkerLauncher(
                financeService, queueService,
                eventNotifierWrapper,
                eventsMonitor,
                executorService
        );

        workerLauncher.launch();
        workerLauncher.launch();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executorService, times(4)).execute(runnableCaptor.capture());

        Assertions.assertEquals(
                List.of(Creator.class, Validator.class, Worker.class, Reporter.class),
                runnableCaptor.getAllValues().stream().map(Object::getClass).toList()
        );
        verifyNoMoreInteractions(executorService);
    }

    @Test
    public void testShutdownStopsExecutor() {
        FinanceService financeService = mock(FinanceService.class);
        QueueService queueService = mock(QueueService.class);
        EventNotifierWrapper eventNotifierWrapper = mock(EventNotifierWrapper.class);
        EventsMonitor eventsMonitor = mock(EventsMonitor.class);
        ExecutorService executorService = mock(ExecutorService.class);

        when(queueService.getCreationQueue()).thenReturn(new PriorityBlockingQueue<>());
        when(queueService.getValidationQueue()).thenReturn(new PriorityBlockingQueue<>());

        WorkerLauncher workerLauncher = new WorkerLauncher(
                financeService, queueService,
                eventNotifierWrapper,
                eventsMonitor,
                executorService
        );

        workerLauncher.shutdown();

        verify(executorService).shutdownNow();
    }
}
