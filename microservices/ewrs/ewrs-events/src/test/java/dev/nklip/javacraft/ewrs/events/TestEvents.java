package dev.nklip.javacraft.ewrs.events;

import dev.nklip.javacraft.ewrs.events.impl.AcceptedEvent;
import dev.nklip.javacraft.ewrs.events.impl.CompletedEvent;
import dev.nklip.javacraft.ewrs.events.impl.CreatedEvent;
import dev.nklip.javacraft.ewrs.events.impl.RejectedEvent;
import dev.nklip.javacraft.ewrs.events.impl.RunningEvent;
import java.time.Instant;
import java.util.UUID;

public final class TestEvents {

    private static final Instant BASE_TIME = Instant.parse("2026-04-18T08:00:00Z");

    private TestEvents() {
    }

    public static CreatedEvent createdEvent(UUID eventId, int taskId, Priority priority, String title,
                                            String budgetCode, int estimate, String actor, long streamVersion) {
        return new CreatedEvent(eventId, taskId, priority, title, budgetCode, estimate,
                BASE_TIME.plusSeconds(streamVersion), actor, "corr-" + taskId, streamVersion);
    }

    public static AcceptedEvent acceptedEvent(UUID eventId, int taskId, Priority priority, String title,
                                              String budgetCode, int estimate, String actor, long streamVersion) {
        return new AcceptedEvent(eventId, taskId, priority, title, budgetCode, estimate,
                BASE_TIME.plusSeconds(streamVersion), actor, "corr-" + taskId, streamVersion);
    }

    public static RunningEvent runningEvent(UUID eventId, int taskId, Priority priority, String title,
                                            String budgetCode, int estimate, String actor, long streamVersion) {
        return new RunningEvent(eventId, taskId, priority, title, budgetCode, estimate,
                BASE_TIME.plusSeconds(streamVersion), actor, "corr-" + taskId, streamVersion);
    }

    public static CompletedEvent completedEvent(UUID eventId, int taskId, Priority priority, String title,
                                                String budgetCode, int estimate, String actor, long streamVersion) {
        return new CompletedEvent(eventId, taskId, priority, title, budgetCode, estimate,
                BASE_TIME.plusSeconds(streamVersion), actor, "corr-" + taskId, streamVersion);
    }

    public static RejectedEvent rejectedEvent(UUID eventId, int taskId, Priority priority, String title,
                                              String budgetCode, int estimate, String actor, long streamVersion,
                                              String reason) {
        return new RejectedEvent(eventId, taskId, priority, title, budgetCode, estimate,
                BASE_TIME.plusSeconds(streamVersion), actor, "corr-" + taskId, streamVersion, reason);
    }
}
