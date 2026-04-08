package dev.nklip.javacraft.ses.simulator.model;

import dev.nklip.javacraft.ses.events.Priority;

/**
 * Created by nikilipa on 7/23/16.
 */
public class Task implements Comparable<Task> {

    private final Priority priority;
    private final int id;
    private final String title;
    private final String financeCode;
    private final int estimate;

    public Task(Priority priority, int id, String title, String financeCode, int estimate) {
        this.id = id;
        this.priority = priority;
        this.title = title;
        this.financeCode = financeCode;
        this.estimate = estimate;
    }

    public Priority getPriority() {
        return priority;
    }

    public String getTitle() {
        return title;
    }

    public int getId() {
        return id;
    }

    public String getFinanceCode() {
        return financeCode;
    }

    public int getEstimate() {
        return estimate;
    }

    @Override
    public int compareTo(Task that) {
        if (this.priority.getSort() > that.getPriority().getSort()) {
            return 1;
        } else if (this.priority.getSort() == that.getPriority().getSort()) {
            if (this.id > that.id) {
                return 1;
            } else if (that.id > this.id) {
                return -1;
            }
            return 0;
        } else {
            return -1;
        }
    }

}
