package dev.nklip.javacraft.ses.events.impl;

import dev.nklip.javacraft.ses.events.EventStatus;
import dev.nklip.javacraft.ses.events.Priority;

/**
 * Created by nikilipa on 7/26/16.
 */
public class CreatedEvent extends BaseEvent {

    public CreatedEvent(Priority priority, String title, String financeCode, int estimate) {
        super(priority, title, financeCode, estimate, EventStatus.CREATED);
    }
}
