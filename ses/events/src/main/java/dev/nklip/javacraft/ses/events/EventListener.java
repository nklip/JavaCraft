package dev.nklip.javacraft.ses.events;

import java.util.function.Consumer;

/**
 * Functional listener contract for the {@code ses} event bus.
 *
 * <p>It extends {@link Consumer} so listeners can be expressed as simple lambdas or method references.
 * The event subsystem only needs one capability from a subscriber: accept an event of a specific type
 * and react to it. That is why this interface stays deliberately minimal.
 *
 * <p>Examples in this module:
 * <ul>
 *     <li>{@link EventsMonitor} subscribes listeners that update its in-memory projection</li>
 *     <li>tests register lightweight listeners that capture emitted events into maps or lists</li>
 * </ul>
 */
@FunctionalInterface
public interface EventListener<Event> extends Consumer<Event> {
}
