package dev.nklip.javacraft.ses.simulator.flow;

import java.util.ArrayList;
import java.util.Comparator;
import dev.nklip.javacraft.ses.events.Event;
import dev.nklip.javacraft.ses.events.EventsMonitor;

import java.util.List;

/**
 * Periodically renders the current workflow snapshot.
 *
 * <p>The reporter does not inspect queues and does not know how tasks move through the pipeline.
 * Instead, it relies entirely on {@link EventsMonitor}, which already contains the latest known
 * event for each task id. That keeps reporting decoupled from the producer threads.
 *
 * <p>Sequence:
 * <pre>{@code
 * Reporter.run()
 *     -> sleep(3000)
 *     -> EventsMonitor.getStorage()
 *     -> sort by status and priority
 *     -> print current snapshot
 * }</pre>
 */
public class Reporter implements Runnable {

    private static final Comparator<Event> REPORT_ORDER = Comparator
            .comparingInt((Event e) -> e.getStatus().getSort())
            .thenComparingInt(e -> e.getPriority().getSort());

    private final EventsMonitor eventsMonitor;

    public Reporter(EventsMonitor eventsMonitor) {
        this.eventsMonitor = eventsMonitor;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                busyWait();

                List<Event> eventList = getSortedEventsForReport();

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
            Thread.currentThread().interrupt();
        }
    }

    List<Event> getSortedEventsForReport() {
        List<Event> eventList = new ArrayList<>(eventsMonitor.getStorage());
        eventList.sort(REPORT_ORDER);
        return eventList;
    }

    void busyWait() throws InterruptedException {
        Thread.sleep(3000);
    }

}
