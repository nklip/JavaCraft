package dev.nklip.javacraft.ses.events;

/**
 * Created by nikilipa on 7/23/16.
 */
public interface Event extends Comparable<Event> {

    Priority getPriority();

    String getTitle();

    String getFinanceCode();

    int getEstimate();

    EventStatus getStatus();

    void setException(Exception e);

    boolean equals(Object that);

    int hashCode();

}
