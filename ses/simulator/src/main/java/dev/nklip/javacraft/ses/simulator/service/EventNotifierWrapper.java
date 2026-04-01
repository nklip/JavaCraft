package dev.nklip.javacraft.ses.simulator.service;

import dev.nklip.javacraft.ses.events.impl.*;
import dev.nklip.javacraft.ses.simulator.model.Task;
import dev.nklip.javacraft.ses.events.Event;
import dev.nklip.javacraft.ses.events.EventNotifier;
import dev.nklip.javacraft.ses.simulator.Application;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Created by nikilipa on 8/29/16.
 */
@Service
public class EventNotifierWrapper {

    private static final EventNotifier eventNotifier  = ((Supplier<EventNotifier>) () -> {
        ApplicationContext context = new ClassPathXmlApplicationContext(Application.CONTEXT);

        return context.getBean(EventNotifier.class);
    }).get();

    public static void createdEvent(Task task) {
        notify(new CreatedEvent(task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    public static void acceptedEvent(Task task) {
        notify(new AcceptedEvent(task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    public static void rejectedEvent(Task task) {
        notify(new RejectedEvent(task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    public static void runningEvent(Task task) {
        notify(new RunningEvent(task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    public static void completedEvent(Task task) {
        notify(new CompletedEvent(task.getPriority(), task.getTitle(), task.getFinanceCode(), task.getEstimate()));
    }

    static <T extends Event> void notify(T event) {
        eventNotifier.notify(event);
    }

}
