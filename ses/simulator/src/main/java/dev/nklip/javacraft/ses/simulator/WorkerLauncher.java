package dev.nklip.javacraft.ses.simulator;

import dev.nklip.javacraft.ses.simulator.flow.Creator;
import dev.nklip.javacraft.ses.simulator.flow.Reporter;
import dev.nklip.javacraft.ses.simulator.flow.Validator;
import dev.nklip.javacraft.ses.simulator.flow.Worker;
import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.events.EventsMonitor;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;
import dev.nklip.javacraft.ses.simulator.service.FinanceService;
import dev.nklip.javacraft.ses.simulator.service.QueueService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Orchestrates the simulator pipeline.
 *
 * <p>This is the top-level coordination class of the runnable {@code ses-simulator} module. It owns the two
 * queues that connect the stages, the shared services used by those stages, and the executor that hosts the
 * four long-lived worker loops.
 *
 * <p>The launcher exists so that the rest of the code can stay focused:
 * <ul>
 *     <li>{@link Creator} only creates tasks</li>
 *     <li>{@link Validator} only validates finance capacity</li>
 *     <li>{@link Worker} only executes accepted tasks</li>
 *     <li>{@link Reporter} only renders the current monitor snapshot</li>
 * </ul>
 *
 * <p>Sequence:
 * <pre>{@code
 * SesApplication.run(context)
 *     -> context.getBean(WorkerLauncher)
 *     -> WorkerLauncher.launch()
 *     -> start Creator(fromCreatorQueue)
 *     -> start Validator(fromCreatorQueue, fromValidatorQueue)
 *     -> start Worker(fromValidatorQueue)
 *     -> start Reporter(eventsMonitor)
 * }</pre>
 *
 * <p>The {@code launched} guard makes repeated {@link #launch()} calls safe and prevents the same pipeline from
 * being started twice. Shutdown is centralized here as well so Spring can stop all running threads when the
 * application context closes.
 */
@Component
public class WorkerLauncher implements DisposableBean {

    private final FinanceService financeService;
    private final EventNotifierWrapper eventNotifierWrapper;
    private final EventsMonitor eventsMonitor;
    private final ExecutorService executorService;
    private final PriorityBlockingQueue<Task> fromCreator;
    private final PriorityBlockingQueue<Task> fromValidator;
    private final AtomicBoolean launched = new AtomicBoolean();

    @Autowired
    public WorkerLauncher(
            FinanceService financeService,
            QueueService queueService,
            EventNotifierWrapper eventNotifierWrapper,
            EventsMonitor eventsMonitor
    ) {
        this(
                financeService, queueService,
                eventNotifierWrapper,
                eventsMonitor,
                Executors.newFixedThreadPool(4)
        );
    }

    WorkerLauncher(
            FinanceService financeService,
            QueueService queueService,
            EventNotifierWrapper eventNotifierWrapper,
            EventsMonitor eventsMonitor,
            ExecutorService executorService
    ) {
        this.financeService = financeService;
        this.eventNotifierWrapper = eventNotifierWrapper;
        this.eventsMonitor = eventsMonitor;
        this.executorService = executorService;

        this.fromCreator = queueService.getCreationQueue();
        this.fromValidator = queueService.getValidationQueue();
    }

    public void launch() {
        if (!launched.compareAndSet(false, true)) {
            return;
        }

        executorService.execute(new Creator(fromCreator, eventNotifierWrapper));
        executorService.execute(new Validator(financeService, eventNotifierWrapper, fromCreator, fromValidator));
        executorService.execute(new Worker(fromValidator, eventNotifierWrapper));
        executorService.execute(new Reporter(eventsMonitor));
    }

    void shutdown() {
        executorService.shutdownNow();
    }

    @Override
    public void destroy() {
        shutdown();
    }
}
