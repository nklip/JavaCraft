package dev.nklip.javacraft.ses.events;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class EventStatusTest {

    @Test
    public void testValueAndSort() {
        Assertions.assertAll(
                () -> assertStatus(EventStatus.CREATED, "Created", 0),
                () -> assertStatus(EventStatus.ACCEPTED, "Accepted", 1),
                () -> assertStatus(EventStatus.RUNNING, "In running", 2),
                () -> assertStatus(EventStatus.COMPLETED, "Completed", 3),
                () -> assertStatus(EventStatus.REJECTED, "Rejected", 4)
        );
    }

    private static void assertStatus(EventStatus eventStatus, String textValue, int sort) {
        Assertions.assertEquals(textValue, eventStatus.toString());
        Assertions.assertEquals(sort, eventStatus.getSort());
    }
}
