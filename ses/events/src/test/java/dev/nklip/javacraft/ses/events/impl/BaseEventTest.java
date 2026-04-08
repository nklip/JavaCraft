package dev.nklip.javacraft.ses.events.impl;

import dev.nklip.javacraft.ses.events.Event;
import dev.nklip.javacraft.ses.events.Priority;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Created by nikilipa on 7/26/16.
 */
public class BaseEventTest {

    @Test
    public void testEqualsContains() {
        String financeCode = "TestFinanceCode";

        List<Event> eventList = new ArrayList<>();
        eventList.add(new CreatedEvent(1, Priority.BLOCKER, "Task 1", financeCode, 10));
        eventList.add(new AcceptedEvent(2, Priority.CRITICAL, "Task 2", financeCode, 15));
        eventList.add(new RunningEvent(3, Priority.NORMAL, "Task 3", financeCode, 20));
        eventList.add(new CompletedEvent(4, Priority.NORMAL, "Task 4", financeCode, 25));

        Assertions.assertTrue(eventList.contains(new CreatedEvent(1, Priority.MINOR, "Different title", financeCode, 99)));
        Assertions.assertTrue(eventList.contains(new AcceptedEvent(2, Priority.CRITICAL, "Task 2", financeCode, 15)));
        Assertions.assertTrue(eventList.contains(new RunningEvent(3, Priority.NORMAL, "Task 3", financeCode, 20)));
        Assertions.assertFalse(eventList.contains(new CreatedEvent(10, Priority.BLOCKER, "Task 1", financeCode, 10)));

    }

    @Test
    public void testCompareTo() {
        String financeCode = "TestFinanceCode";

        List<Event> sortedEventList = new ArrayList<>();
        sortedEventList.add(new CompletedEvent(4, Priority.NORMAL,  "Task 4", financeCode, 25));
        sortedEventList.add(new CompletedEvent(3, Priority.NORMAL,  "Task 3", financeCode, 50));
        sortedEventList.add(new AcceptedEvent(2, Priority.CRITICAL, "Task 2", financeCode, 15));
        sortedEventList.add(new RunningEvent(1, Priority.CRITICAL,  "Task 1", financeCode, 20));
        sortedEventList.add(new CreatedEvent(0, Priority.BLOCKER,   "Task 0", financeCode, 10));

        Collections.sort(sortedEventList);

        Assertions.assertEquals("Task 0", sortedEventList.get(0).getTitle());
        Assertions.assertEquals("Task 1", sortedEventList.get(1).getTitle());
        Assertions.assertEquals("Task 2", sortedEventList.get(2).getTitle());
        Assertions.assertEquals("Task 3", sortedEventList.get(3).getTitle());
        Assertions.assertEquals("Task 4", sortedEventList.get(4).getTitle());

    }

    @Test
    public void testGettersHashCodeAndEqualsWithNonEvent() {
        CreatedEvent createdEvent = new CreatedEvent(7, Priority.MAJOR, "Task 7", "Finance-7", 42);

        Assertions.assertAll(
                () -> Assertions.assertEquals(7, createdEvent.getTaskId()),
                () -> Assertions.assertEquals(Priority.MAJOR, createdEvent.getPriority()),
                () -> Assertions.assertEquals("Task 7", createdEvent.getTitle()),
                () -> Assertions.assertEquals("Finance-7", createdEvent.getFinanceCode()),
                () -> Assertions.assertEquals(42, createdEvent.getEstimate()),
                () -> Assertions.assertEquals(dev.nklip.javacraft.ses.events.EventStatus.CREATED, createdEvent.getStatus()),
                () -> Assertions.assertEquals(Integer.hashCode(7), createdEvent.hashCode()),
                () -> Assertions.assertFalse(createdEvent.equals("Task 7"))
        );
    }

    @Test
    public void testCompareToFallsBackToEstimateAndTaskId() {
        AcceptedEvent lowerEstimate = new AcceptedEvent(8, Priority.NORMAL, "Task 8", "Finance-8", 10);
        AcceptedEvent higherEstimate = new AcceptedEvent(9, Priority.NORMAL, "Task 9", "Finance-9", 20);
        AcceptedEvent lowerTaskId = new AcceptedEvent(3, Priority.NORMAL, "Task 3", "Finance-3", 20);
        AcceptedEvent higherTaskId = new AcceptedEvent(4, Priority.NORMAL, "Task 4", "Finance-4", 20);

        Assertions.assertAll(
                () -> Assertions.assertTrue(higherEstimate.compareTo(lowerEstimate) < 0),
                () -> Assertions.assertTrue(lowerEstimate.compareTo(higherEstimate) > 0),
                () -> Assertions.assertTrue(lowerTaskId.compareTo(higherTaskId) < 0),
                () -> Assertions.assertTrue(higherTaskId.compareTo(lowerTaskId) > 0)
        );
    }
}
