package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;
import dev.nklip.javacraft.ses.simulator.service.FinanceService;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Finance gate between created tasks and executable tasks.
 *
 * <p>The validator is responsible for turning "a task exists" into one of two outcomes:
 * accepted or rejected. It polls the queue filled by {@link Creator}, asks {@link FinanceService}
 * whether the task can be funded, updates the finance budget when accepted, emits the matching event,
 * and forwards only accepted tasks to the worker queue.
 *
 * <p>Sequence:
 * <pre>{@code
 * Validator.run()
 *     -> fromCreator.poll()
 *     -> FinanceService.isEnoughMoney(task.financeCode, task.estimate)
 *     -> accepted:
 *            FinanceService.updateFinance(...)
 *            EventNotifierWrapper.acceptedEvent(task)
 *            toWorker.add(task)
 *        rejected:
 *            EventNotifierWrapper.rejectedEvent(task)
 *     -> sleep(...)
 * }</pre>
 */
public class Validator implements Runnable {

    private final FinanceService financeService;
    private final EventNotifierWrapper eventNotifierWrapper;
    private final PriorityBlockingQueue<Task> fromCreator;
    private final PriorityBlockingQueue<Task> toWorker;

    public Validator(
            FinanceService financeService,
            EventNotifierWrapper eventNotifierWrapper,
            PriorityBlockingQueue<Task> fromCreator,
            PriorityBlockingQueue<Task> toWorker
    ) {
        this.financeService = financeService;
        this.eventNotifierWrapper = eventNotifierWrapper;
        this.fromCreator = fromCreator;
        this.toWorker = toWorker;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Task task = fromCreator.poll();
                if (task != null) {
                    if (financeService.isEnoughMoney(task.getFinanceCode(), task.getEstimate())) {
                        System.out.printf("The task '%s' with priority '%s' was accepted%n", task.getTitle(), task.getPriority());
                        financeService.updateFinance(task.getFinanceCode(), task.getEstimate());

                        eventNotifierWrapper.acceptedEvent(task);
                        toWorker.add(task);
                    } else {
                        eventNotifierWrapper.rejectedEvent(task);
                        System.out.printf("The task '%s' with priority '%s' was rejected%n", task.getTitle(), task.getPriority());
                    }
                }

                long sleep = ThreadLocalRandom.current().nextInt(3000, 4000);
                System.out.printf("The validator thread decided to sleep %s millisec%n", sleep);
                busyWait(sleep);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void busyWait(long sleep) throws InterruptedException {
        Thread.sleep(sleep);
    }
}
