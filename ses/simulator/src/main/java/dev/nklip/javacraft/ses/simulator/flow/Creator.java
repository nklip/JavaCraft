package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.simulator.db.FinanceDao;
import dev.nklip.javacraft.ses.events.Priority;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by nikilipa on 7/23/16.
 */
public class Creator implements Runnable {

    private static final AtomicInteger counter = new AtomicInteger();

    private final PriorityBlockingQueue<Task> priorityQueue;

    public Creator(PriorityBlockingQueue<Task> priorityQueue) {
        this.priorityQueue = priorityQueue;
    }

    @Override
    public void run() {
        try {
            while (true) {

                int priority = ThreadLocalRandom.current().nextInt(0, 4);
                int code = ThreadLocalRandom.current().nextInt(0, 2);
                String financeCode = switch (code) {
                    case 0 -> FinanceDao.FINANCE_CODE_GENERAL;
                    case 1 -> FinanceDao.FINANCE_CODE_SUPPORT;
                    case 2 -> FinanceDao.FINANCE_CODE_MIGRATION;
                    default -> "Unknown finance code";
                };
                int estimate = ThreadLocalRandom.current().nextInt(2, 40);

                Task task = new Task(Priority.valueOf(priority), counter.get(), String.format("Task #%s", counter.getAndIncrement()), financeCode, estimate);

                EventNotifierWrapper.createdEvent(task);

                System.out.printf("Task %s with priority %s was created%n", task.getTitle(), task.getPriority());
                priorityQueue.add(task);

                long sleep = ThreadLocalRandom.current().nextInt(2000, 3000);
                System.out.printf("The creator thread decided to sleep '%s' millisec%n", sleep);

                Thread.sleep(sleep);
            }
        } catch (InterruptedException e) {
            System.err.println(e.getMessage());
        }
    }
}
