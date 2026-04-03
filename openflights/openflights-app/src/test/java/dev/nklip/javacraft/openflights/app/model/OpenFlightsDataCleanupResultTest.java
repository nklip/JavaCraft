package dev.nklip.javacraft.openflights.app.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenFlightsDataCleanupResultTest {

    @Test
    void totalDeletedSumsAllDeletedRows() {
        OpenFlightsDataCleanupResult result = new OpenFlightsDataCleanupResult(1, 2, 3, 4, 5, 6);

        Assertions.assertEquals(21L, result.totalDeleted());
    }
}
