package dev.nklip.javacraft.shardshop.workload.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Backend HTTP requests of one workload by purpose, since process start. A repeated attempt counts as a request and
 * as a retry. A failed attempt (no response, an error status or an invalid response) counts as an error.
 */
public final class Traffic {
    private final Map<String, Counters> categories = new LinkedHashMap<>();

    Traffic(List<String> categories) {
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("Traffic needs at least one category");
        }
        for (String category : categories) {
            if (this.categories.put(category, new Counters()) != null) {
                throw new IllegalArgumentException("Traffic categories must be unique");
            }
        }
    }

    /** Counts one HTTP request of the category; {@code retry} marks a repeated attempt. */
    public void sent(String category, boolean retry) {
        Counters counters = counters(category);
        counters.requests.increment();
        if (retry) {
            counters.retries.increment();
        }
    }

    /** Counts one failed HTTP request of the category. */
    public void failed(String category) {
        counters(category).errors.increment();
    }

    List<ControlStatus.TrafficCategory> snapshot() {
        return categories.entrySet().stream().map(entry -> new ControlStatus.TrafficCategory(entry.getKey(),
                entry.getValue().requests.sum(), entry.getValue().retries.sum(), entry.getValue().errors.sum())).toList();
    }

    private Counters counters(String category) {
        Counters counters = categories.get(category);
        if (counters == null) {
            throw new IllegalArgumentException("Unknown traffic category");
        }
        return counters;
    }

    private static final class Counters {
        private final LongAdder requests = new LongAdder();
        private final LongAdder retries = new LongAdder();
        private final LongAdder errors = new LongAdder();
    }
}
