package dev.nklip.javacraft.shardshop.workload.control;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrafficTest {
    @Test
    void countsRequestsRetriesAndErrorsByPurposeInDeclarationOrder() {
        Traffic traffic = new Traffic(List.of("creation", "verification"));
        traffic.sent("verification", false);
        traffic.sent("creation", false);
        traffic.failed("creation");
        traffic.sent("creation", true);
        assertEquals(List.of(new ControlStatus.TrafficCategory("creation", 2, 1, 1),
                new ControlStatus.TrafficCategory("verification", 1, 0, 0)), traffic.snapshot());
    }

    @Test
    void rejectsUnknownEmptyAndRepeatedCategories() {
        Traffic traffic = new Traffic(List.of("reads"));
        assertThrows(IllegalArgumentException.class, () -> traffic.sent("writes", false));
        assertThrows(IllegalArgumentException.class, () -> traffic.failed("writes"));
        assertThrows(IllegalArgumentException.class, () -> new Traffic(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Traffic(List.of("reads", "reads")));
    }
}
