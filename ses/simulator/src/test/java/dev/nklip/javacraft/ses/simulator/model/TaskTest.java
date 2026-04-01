package dev.nklip.javacraft.ses.simulator.model;

import dev.nklip.javacraft.ses.events.Priority;
import dev.nklip.javacraft.ses.simulator.db.FinanceDao;

import java.util.PriorityQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Created by nikilipa on 7/23/16.
 */
public class TaskTest {

    @Test
    public void testPrioritySort() {

        PriorityQueue<Task> priorityQueue = new PriorityQueue<>();
        priorityQueue.add(new Task(Priority.MINOR, 1, "Task 1", FinanceDao.FINANCE_CODE_SUPPORT, 20));
        priorityQueue.add(new Task(Priority.MAJOR, 2, "Task 2", FinanceDao.FINANCE_CODE_MIGRATION, 50));
        priorityQueue.add(new Task(Priority.BLOCKER, 3, "Task 3", FinanceDao.FINANCE_CODE_GENERAL, 100));
        priorityQueue.add(new Task(Priority.MAJOR, 4, "Task 4", FinanceDao.FINANCE_CODE_GENERAL, 100));

        Assertions.assertEquals(Priority.BLOCKER, priorityQueue.poll().getPriority());
        Assertions.assertEquals(Priority.MAJOR, priorityQueue.poll().getPriority());
        Assertions.assertEquals(Priority.MAJOR, priorityQueue.poll().getPriority());
        Assertions.assertEquals(Priority.MINOR, priorityQueue.poll().getPriority());

    }
}
