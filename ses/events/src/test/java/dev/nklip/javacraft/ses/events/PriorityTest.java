package dev.nklip.javacraft.ses.events;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PriorityTest {

    @Test
    public void testSortValues() {
        Assertions.assertAll(
                () -> Assertions.assertEquals(0, Priority.BLOCKER.getSort()),
                () -> Assertions.assertEquals(1, Priority.CRITICAL.getSort()),
                () -> Assertions.assertEquals(2, Priority.MAJOR.getSort()),
                () -> Assertions.assertEquals(3, Priority.NORMAL.getSort()),
                () -> Assertions.assertEquals(4, Priority.MINOR.getSort())
        );
    }
}
