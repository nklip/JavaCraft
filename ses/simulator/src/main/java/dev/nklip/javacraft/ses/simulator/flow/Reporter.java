package dev.nklip.javacraft.ses.simulator.flow;

import java.util.Comparator;
import dev.nklip.javacraft.ses.events.Event;
import dev.nklip.javacraft.ses.events.EventsMonitor;

import java.util.List;

/**
 * Created by nikilipa on 7/26/16.
 */
public class Reporter implements Runnable {

    @Override
    public void run() {
        try {
            while (true) {
                Thread.sleep(3000);

                List<Event> eventList = EventsMonitor.getStorage();

                // sort by status
                // CREATED -> ACCEPTED -> RUNNING -> COMPLETED -> REJECTED
                eventList.sort(Comparator
                        .comparingInt((Event e) -> e.getStatus().getSort())
                        .thenComparingInt(e -> e.getPriority().getSort())
                );

                if (!eventList.isEmpty()) {
                    System.out.println("*********************************************");
                    for (Event event : eventList) {
                        System.out.printf(
                                "    Event priority = %s, title = %s, code = %s, estimate = %s, status = %s;%n",
                                event.getPriority(),
                                event.getTitle(),
                                event.getFinanceCode(),
                                event.getEstimate(),
                                event.getStatus()
                        );
                    }
                    System.out.println("*********************************************");
                } else {
                    System.out.println("No events!");
                }
            }
        } catch (InterruptedException e) {
            System.err.println(e.getMessage());
        }
    }

}
