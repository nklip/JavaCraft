package dev.nklip.javacraft.ses.simulator.flow;

import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;
import dev.nklip.javacraft.ses.simulator.service.FinanceService;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created by nikilipa on 7/23/16.
 */
public class Validator implements Runnable {

    private final FinanceService financeService;
    private final PriorityBlockingQueue<Task> fromCreator;
    private final PriorityBlockingQueue<Task> toWorker;

    public Validator(FinanceService financeService, PriorityBlockingQueue<Task> fromCreator, PriorityBlockingQueue<Task> toWorker) {
        this.financeService = financeService;
        this.fromCreator = fromCreator;
        this.toWorker = toWorker;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Task task = fromCreator.poll();
                if (task != null) {
                    if (financeService.isEnoughMoney(task.getFinanceCode(), task.getEstimate())) {
                        System.out.printf("The task '%s' with priority '%s' was accepted%n", task.getTitle(), task.getPriority());
                        financeService.updateFinance(task.getFinanceCode(), task.getEstimate());

                        EventNotifierWrapper.acceptedEvent(task);
                        toWorker.add(task);
                    } else {
                        EventNotifierWrapper.rejectedEvent(task);
                        System.out.printf("The task '%s' with priority '%s' was rejected%n", task.getTitle(), task.getPriority());
                    }
                }

                long sleep = ThreadLocalRandom.current().nextInt(3000, 4000);
                System.out.printf("The validator thread decided to sleep %s millisec%n", sleep);
                Thread.sleep(sleep);
            }
        } catch (InterruptedException e) {
            System.err.println(e.getMessage());
        }
    }
}
