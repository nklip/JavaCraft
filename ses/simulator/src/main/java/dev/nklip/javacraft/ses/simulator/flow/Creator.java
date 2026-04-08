package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.simulator.db.FinanceDao;
import dev.nklip.javacraft.ses.events.Priority;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;

import java.util.random.RandomGenerator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Produces random tasks and places them on the first queue of the simulator pipeline.
 *
 * <p>This stage is the source of new work. It creates a task id, chooses a priority, chooses a finance code,
 * calculates an estimate, publishes a {@code CreatedEvent}, and then hands the task to the validation queue.
 * The rest of the simulator reacts to that task later; the creator does not know whether the task will be
 * accepted, rejected, or completed.
 *
 * <p>Sequence:
 * <pre>{@code
 * Creator.run()
 *     -> createTask(random)
 *     -> EventNotifierWrapper.createdEvent(task)
 *     -> priorityQueue.add(task)
 *     -> sleep(...)
 * }</pre>
 */
public class Creator implements Runnable {

    private final PriorityBlockingQueue<Task> priorityQueue;
    private final EventNotifierWrapper eventNotifierWrapper;
    private final AtomicInteger taskCounter = new AtomicInteger();

    public Creator(PriorityBlockingQueue<Task> priorityQueue, EventNotifierWrapper eventNotifierWrapper) {
        this.priorityQueue = priorityQueue;
        this.eventNotifierWrapper = eventNotifierWrapper;
    }

    Task createTask(RandomGenerator randomGenerator) {
        Priority priority = Priority.values()[randomGenerator.nextInt(Priority.values().length)];
        String financeCode = FinanceDao.getSupportedFinanceCodes().get(randomGenerator.nextInt(FinanceDao.getSupportedFinanceCodes().size()));
        int estimate = randomGenerator.nextInt(2, 40);
        int taskId = taskCounter.getAndIncrement();

        return new Task(priority, taskId, "Task #" + taskId, financeCode, estimate);
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Task task = createTask(ThreadLocalRandom.current());

                eventNotifierWrapper.createdEvent(task);

                System.out.printf("Task %s with priority %s was created%n", task.getTitle(), task.getPriority());
                priorityQueue.add(task);

                long sleep = ThreadLocalRandom.current().nextInt(2000, 3000);
                System.out.printf("The creator thread decided to sleep '%s' millisec%n", sleep);
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
