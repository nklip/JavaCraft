package dev.nklip.javacraft.ewrs.scenarios.api;

import dev.nklip.javacraft.ewrs.events.EventStatus;
import java.util.List;

/**
 * Supported scenario templates that the standalone scenario driver can execute.
 * The enum keeps the catalog deterministic so docs, demos, and black-box tests talk about the same named workflows.
 */
public enum ScenarioType {
    CREATE_ONLY(
            "Creates a work request and leaves it in CREATED state.",
            List.of(EventStatus.CREATED)
    ),
    HAPPY_PATH(
            "Creates, approves, starts, and completes a work request.",
            List.of(EventStatus.COMPLETED)
    ),
    APPROVAL_DENIED(
            "Creates a request whose approval is rejected by the budget policy.",
            List.of(EventStatus.REJECTED)
    ),
    EXPLICIT_REJECT(
            "Creates a request and explicitly rejects it with an operator reason.",
            List.of(EventStatus.REJECTED)
    ),
    INVALID_START(
            "Creates a request and intentionally attempts START before approval to confirm the conflict path.",
            List.of(EventStatus.CREATED)
    ),
    MIXED_LOAD(
            "Cycles through happy-path, budget-denied, explicit-reject, and invalid-start variants for deterministic load.",
            List.of(EventStatus.COMPLETED, EventStatus.REJECTED, EventStatus.CREATED)
    );

    private final String description;
    private final List<EventStatus> expectedTerminalStatuses;

    ScenarioType(String description, List<EventStatus> expectedTerminalStatuses) {
        this.description = description;
        this.expectedTerminalStatuses = expectedTerminalStatuses;
    }

    public String description() {
        return description;
    }

    public List<EventStatus> expectedTerminalStatuses() {
        return expectedTerminalStatuses;
    }
}
