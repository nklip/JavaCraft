package dev.nklip.javacraft.ewrs.app.integration;

import dev.nklip.javacraft.ewrs.app.AbstractPostgresIntegrationTest;
import dev.nklip.javacraft.ewrs.app.repository.EventStoreRepository;
import dev.nklip.javacraft.ewrs.events.Event;
import dev.nklip.javacraft.ewrs.events.Priority;
import dev.nklip.javacraft.ewrs.events.impl.AcceptedEvent;
import dev.nklip.javacraft.ewrs.events.impl.CreatedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class EventStoreRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private EventStoreRepository eventStoreRepository;

    @Test
    void appendStoresEventMetadataAndReloadsHistory() {
        int taskId = eventStoreRepository.nextTaskId();
        Event createdEvent = new CreatedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000001001"),
                taskId,
                Priority.CRITICAL,
                "Rotate access keys",
                "PLATFORM-2026",
                25,
                Instant.parse("2026-04-18T10:00:00Z"),
                "Nikita",
                "corr-store",
                1L
        );

        long storeId = eventStoreRepository.append(createdEvent);
        List<Event> history = eventStoreRepository.findEventHistory(taskId);

        Assertions.assertAll(
                () -> Assertions.assertEquals(1L, storeId),
                () -> Assertions.assertEquals(1, history.size()),
                () -> Assertions.assertEquals(createdEvent.getEventId(), history.getFirst().getEventId()),
                () -> Assertions.assertEquals("Nikita", history.getFirst().getActor()),
                () -> Assertions.assertEquals("PLATFORM-2026", history.getFirst().getBudgetCode())
        );
    }

    @Test
    void appendEnforcesUniqueTaskAndStreamVersionOrdering() {
        int taskId = eventStoreRepository.nextTaskId();
        eventStoreRepository.append(new CreatedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000001002"),
                taskId,
                Priority.NORMAL,
                "Create first",
                "OPS-2026",
                10,
                Instant.parse("2026-04-18T10:00:01Z"),
                "Requester",
                "corr-1",
                1L
        ));

        Assertions.assertThrows(DataIntegrityViolationException.class, () ->
                eventStoreRepository.append(new AcceptedEvent(
                        UUID.fromString("00000000-0000-0000-0000-000000001003"),
                        taskId,
                        Priority.NORMAL,
                        "Duplicate version",
                        "OPS-2026",
                        10,
                        Instant.parse("2026-04-18T10:00:02Z"),
                        "Reviewer",
                        "corr-2",
                        1L
                ))
        );
    }
}
