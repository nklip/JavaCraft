package dev.nklip.javacraft.ses.events;

/**
 * Common contract for every event emitted by the {@code ses} workflow.
 *
 * <p>An event is the smallest unit of workflow state that other components are allowed to observe.
 * Instead of letting the simulator mutate shared state directly, the simulator emits typed events and
 * consumers such as {@link EventsMonitor} derive their own view from those events.
 *
 * <p>This interface is intentionally small and transport-like:
 * <ul>
 *     <li>{@code taskId} is the stable identity used to correlate all events for the same task</li>
 *     <li>{@code priority}, {@code financeCode}, and {@code estimate} describe the task context</li>
 *     <li>{@code status} describes which stage of the workflow this event represents</li>
 * </ul>
 *
 * <p>Events are also {@link Comparable} so read-side components can display them in a meaningful order
 * without needing custom sorting logic everywhere.
 */
public interface Event extends Comparable<Event> {

    int getTaskId();

    Priority getPriority();

    String getTitle();

    String getFinanceCode();

    int getEstimate();

    EventStatus getStatus();

}
