package dev.nklip.javacraft.ses.simulator.service;

import dev.nklip.javacraft.ses.events.Priority;
import dev.nklip.javacraft.ses.simulator.model.Task;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.PriorityBlockingQueue;

public class QueueServiceTest {

    @Test
    public void testQueuesAreStableAndIndependent() {
        QueueService queueService = new QueueService();
        Task task = new Task(Priority.NORMAL, 4, "Task #4", "finance-4", 14);
        PriorityBlockingQueue<Task> creationQueue = queueService.getCreationQueue();
        PriorityBlockingQueue<Task> validationQueue = queueService.getValidationQueue();

        Assertions.assertNotNull(creationQueue);
        Assertions.assertNotNull(validationQueue);
        Assertions.assertSame(creationQueue, queueService.getCreationQueue());
        Assertions.assertSame(validationQueue, queueService.getValidationQueue());
        Assertions.assertNotSame(creationQueue, validationQueue);

        creationQueue.add(task);

        Assertions.assertEquals(1, creationQueue.size());
        Assertions.assertTrue(validationQueue.isEmpty());
        Assertions.assertSame(task, creationQueue.peek());
    }
}
