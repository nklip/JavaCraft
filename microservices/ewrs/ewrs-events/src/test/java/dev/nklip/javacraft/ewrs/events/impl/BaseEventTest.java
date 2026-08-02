package dev.nklip.javacraft.ewrs.events.impl;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.Priority;
import dev.nklip.javacraft.ewrs.events.TestEvents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Created by nikilipa on 7/26/16.
 */
public class BaseEventTest {

    @Test
    public void testEqualsContains() {
        String financeCode = "TestFinanceCode";
        UUID sharedEventId = UUID.fromString("00000000-0000-0000-0000-000000000101");

        List<Event> eventList = new ArrayList<>();
        eventList.add(TestEvents.createdEvent(sharedEventId, 1, Priority.BLOCKER, "Task 1", financeCode, 10, "alice", 1));
        eventList.add(TestEvents.acceptedEvent(UUID.fromString("00000000-0000-0000-0000-000000000102"),
                2, Priority.CRITICAL, "Task 2", financeCode, 15, "bob", 1));
        eventList.add(TestEvents.runningEvent(UUID.fromString("00000000-0000-0000-0000-000000000103"),
                3, Priority.NORMAL, "Task 3", financeCode, 20, "carol", 1));
        eventList.add(TestEvents.completedEvent(UUID.fromString("00000000-0000-0000-0000-000000000104"),
                4, Priority.NORMAL, "Task 4", financeCode, 25, "dave", 1));

        Assertions.assertTrue(eventList.contains(TestEvents.createdEvent(
                sharedEventId, 999, Priority.MINOR, "Different title", financeCode, 99, "eve", 9
        )));
        Assertions.assertTrue(eventList.contains(TestEvents.acceptedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                2, Priority.CRITICAL, "Task 2", financeCode, 15, "bob", 1
        )));
        Assertions.assertTrue(eventList.contains(TestEvents.runningEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000103"),
                3, Priority.NORMAL, "Task 3", financeCode, 20, "carol", 1
        )));
        Assertions.assertFalse(eventList.contains(TestEvents.createdEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000999"),
                1, Priority.BLOCKER, "Task 1", financeCode, 10, "alice", 1
        )));

    }

    @Test
    public void testCompareTo() {
        String financeCode = "TestFinanceCode";

        List<Event> sortedEventList = new ArrayList<>();
        sortedEventList.add(TestEvents.completedEvent(UUID.fromString("00000000-0000-0000-0000-000000000204"),
                4, Priority.NORMAL, "Task 4", financeCode, 25, "dave", 4));
        sortedEventList.add(TestEvents.completedEvent(UUID.fromString("00000000-0000-0000-0000-000000000203"),
                3, Priority.NORMAL, "Task 3", financeCode, 50, "carol", 3));
        sortedEventList.add(TestEvents.acceptedEvent(UUID.fromString("00000000-0000-0000-0000-000000000202"),
                2, Priority.CRITICAL, "Task 2", financeCode, 15, "bob", 2));
        sortedEventList.add(TestEvents.runningEvent(UUID.fromString("00000000-0000-0000-0000-000000000201"),
                1, Priority.CRITICAL, "Task 1", financeCode, 20, "alice", 1));
        sortedEventList.add(TestEvents.createdEvent(UUID.fromString("00000000-0000-0000-0000-000000000200"),
                0, Priority.BLOCKER, "Task 0", financeCode, 10, "zoe", 1));

        Collections.sort(sortedEventList);

        Assertions.assertEquals("Task 0", sortedEventList.get(0).getTitle());
        Assertions.assertEquals("Task 1", sortedEventList.get(1).getTitle());
        Assertions.assertEquals("Task 2", sortedEventList.get(2).getTitle());
        Assertions.assertEquals("Task 3", sortedEventList.get(3).getTitle());
        Assertions.assertEquals("Task 4", sortedEventList.get(4).getTitle());

    }

    @Test
    public void testGettersHashCodeAndEqualsWithNonEvent() {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        CreatedEvent createdEvent = TestEvents.createdEvent(eventId, 7, Priority.MAJOR, "Task 7",
                "Finance-7", 42, "nikita", 1);

        Assertions.assertAll(
                () -> Assertions.assertEquals(eventId, createdEvent.getEventId()),
                () -> Assertions.assertEquals(7, createdEvent.getTaskId()),
                () -> Assertions.assertEquals(Priority.MAJOR, createdEvent.getPriority()),
                () -> Assertions.assertEquals("Task 7", createdEvent.getTitle()),
                () -> Assertions.assertEquals("Finance-7", createdEvent.getFinanceCode()),
                () -> Assertions.assertEquals("Finance-7", createdEvent.getBudgetCode()),
                () -> Assertions.assertEquals(42, createdEvent.getEstimate()),
                () -> Assertions.assertEquals(EventStatus.CREATED, createdEvent.getStatus()),
                () -> Assertions.assertEquals("nikita", createdEvent.getActor()),
                () -> Assertions.assertEquals(1L, createdEvent.getStreamVersion()),
                () -> Assertions.assertEquals(eventId.hashCode(), createdEvent.hashCode()),
                () -> Assertions.assertNotEquals("Task 7", createdEvent)
        );
    }

    @Test
    public void testCompareToFallsBackToEstimateTaskIdAndOccurredAt() {
        AcceptedEvent lowerEstimate = TestEvents.acceptedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                8, Priority.NORMAL, "Task 8", "Finance-8", 10, "alice", 1
        );
        AcceptedEvent higherEstimate = TestEvents.acceptedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000402"),
                9, Priority.NORMAL, "Task 9", "Finance-9", 20, "bob", 1
        );
        AcceptedEvent lowerTaskId = TestEvents.acceptedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000403"),
                3, Priority.NORMAL, "Task 3", "Finance-3", 20, "carol", 1
        );
        AcceptedEvent higherTaskId = TestEvents.acceptedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000404"),
                4, Priority.NORMAL, "Task 4", "Finance-4", 20, "dave", 1
        );
        AcceptedEvent earlierEvent = TestEvents.acceptedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000405"),
                4, Priority.NORMAL, "Task 4", "Finance-4", 20, "erin", 1
        );
        AcceptedEvent laterEvent = TestEvents.acceptedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000406"),
                4, Priority.NORMAL, "Task 4", "Finance-4", 20, "frank", 2
        );

        Assertions.assertAll(
                () -> Assertions.assertTrue(higherEstimate.compareTo(lowerEstimate) < 0),
                () -> Assertions.assertTrue(lowerEstimate.compareTo(higherEstimate) > 0),
                () -> Assertions.assertTrue(lowerTaskId.compareTo(higherTaskId) < 0),
                () -> Assertions.assertTrue(higherTaskId.compareTo(lowerTaskId) > 0),
                () -> Assertions.assertTrue(earlierEvent.compareTo(laterEvent) < 0),
                () -> Assertions.assertTrue(laterEvent.compareTo(earlierEvent) > 0)
        );
    }
}
