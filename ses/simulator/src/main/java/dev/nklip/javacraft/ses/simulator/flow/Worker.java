package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created by nikilipa on 7/23/16.
 */
public class Worker implements Runnable {

    private final PriorityBlockingQueue<Task> priorityQueue;

    public Worker(PriorityBlockingQueue<Task> priorityQueue) {
        this.priorityQueue = priorityQueue;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Task task = priorityQueue.poll();
                if (task != null) {
                    EventNotifierWrapper.runningEvent(task);

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
                        Thread.sleep(sleep);
                    }

                    EventNotifierWrapper.completedEvent(task);
                } else {
                    long sleep = ThreadLocalRandom.current().nextInt(7000, 12000);
                    System.out.printf("No tasks were found. The worker thread decided to sleep '%s' millisec%n", sleep);
                    Thread.sleep(sleep);
                }
            }
        } catch (InterruptedException e) {
            System.err.println(e.getMessage());
        }

    }
}
