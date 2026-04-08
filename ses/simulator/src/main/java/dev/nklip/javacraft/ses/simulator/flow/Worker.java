package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Final processing stage for accepted tasks.
 *
 * <p>This worker only sees tasks that passed validation. Once it picks a task from the validation queue,
 * it emits a {@code RunningEvent}, simulates work by counting down with sleeps, and finally emits a
 * {@code CompletedEvent}. No finance decisions happen here; those have already been made upstream.
 *
 * <p>Sequence:
 * <pre>{@code
 * Worker.run()
 *     -> validationQueue.poll()
 *     -> EventNotifierWrapper.runningEvent(task)
 *     -> simulate work
 *     -> EventNotifierWrapper.completedEvent(task)
 * }</pre>
 */
public class Worker implements Runnable {

    private final PriorityBlockingQueue<Task> priorityQueue;
    private final EventNotifierWrapper eventNotifierWrapper;

    public Worker(PriorityBlockingQueue<Task> priorityQueue, EventNotifierWrapper eventNotifierWrapper) {
        this.priorityQueue = priorityQueue;
        this.eventNotifierWrapper = eventNotifierWrapper;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Task task = priorityQueue.poll();
                if (task == null) {
                    long sleep = ThreadLocalRandom.current().nextInt(7000, 12000);
                    System.out.printf("No tasks were found. The worker thread decided to sleep '%s' millisec%n", sleep);
                    busyWait(sleep);
                } else {
                    eventNotifierWrapper.runningEvent(task);

                    System.out.printf("The worker thread started to work under %s with priority %s%n", task.getTitle(), task.getPriority());
                    int count = ThreadLocalRandom.current().nextInt(1, 5);
                    for (; count >= 0 ; count--) {
                        long sleep = ThreadLocalRandom.current().nextInt(1000, 2000);
                        System.out.printf(
                                "Task %s with priority %s is 'in running' status. Counts = %s%n",
                                task.getTitle(),
                                task.getPriority(),
                                count
                        );
                        busyWait(sleep);
                    }

                    eventNotifierWrapper.completedEvent(task);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void busyWait(long sleep) throws InterruptedException {
        Thread.sleep(sleep);
    }
}
