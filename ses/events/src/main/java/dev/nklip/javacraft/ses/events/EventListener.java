package dev.nklip.javacraft.ses.events;

import java.util.function.Consumer;

/**
 * Created by nikilipa on 7/23/16.
 */
@FunctionalInterface
public interface EventListener<Event> extends Consumer<Event> {
}

