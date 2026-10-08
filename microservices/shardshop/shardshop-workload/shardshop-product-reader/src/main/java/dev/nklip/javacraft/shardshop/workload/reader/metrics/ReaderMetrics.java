package dev.nklip.javacraft.shardshop.workload.reader.metrics;

import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult;
import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult.Outcome;
import org.apache.hc.core5.pool.PoolStats;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Counts read results by outcome and seller region, with a latency histogram in whole milliseconds, for the current
 * report interval and since start. A latency is rounded up and includes the wait for a connection and all attempts.
 * The pool values are the state of the connection pool when the report is made.
 */
public final class ReaderMetrics {
    private static final int MAX_BUCKET_MS = 10_000;

    private final LongSupplier nanoTime;
    private Window interval;
    private Window total;

    public ReaderMetrics(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        long now = nanoTime.getAsLong();
        this.interval = new Window(now);
        this.total = new Window(now);
    }

    /** Starts the interval and the totals now, when the load starts. */
    public synchronized void begin() {
        long now = nanoTime.getAsLong();
        interval = new Window(now);
        total = new Window(now);
    }

    public synchronized void record(String region, ReadResult<?> result, long latencyNanos) {
        long milliseconds = Math.max(0, (latencyNanos + 999_999) / 1_000_000);
        interval.add(region, result, milliseconds);
        total.add(region, result, milliseconds);
    }

    /** Returns the interval since the previous call, with the current pool state, and starts a new interval. */
    public synchronized Report nextInterval(PoolStats pool) {
        long now = nanoTime.getAsLong();
        Report report = interval.report("interval", now, pool);
        interval = new Window(now);
        return report;
    }

    public synchronized Report total(PoolStats pool) {
        return total.report("total", nanoTime.getAsLong(), pool);
    }

    private static final class Window {
        private final long start;
        private final long[] latencyBuckets = new long[MAX_BUCKET_MS + 1];
        private final Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);
        private final Map<String, Long> regions = new TreeMap<>();
        private long requests;
        private long maxMilliseconds;

        private Window(long start) {
            this.start = start;
        }

        private void add(String region, ReadResult<?> result, long milliseconds) {
            requests++;
            outcomes.merge(result.outcome(), 1L, Long::sum);
            regions.merge(region, 1L, Long::sum);
            latencyBuckets[(int) Math.min(MAX_BUCKET_MS, milliseconds)]++;
            maxMilliseconds = Math.max(maxMilliseconds, milliseconds);
        }

        private Report report(String scope, long now, PoolStats pool) {
            Map<String, Long> latency = new LinkedHashMap<>();
            if (requests > 0) {
                latency.put("p50", percentile(0.50));
                latency.put("p95", percentile(0.95));
                latency.put("p99", percentile(0.99));
                latency.put("max", maxMilliseconds);
            }
            return new Report(scope, (now - start) / 1e9, requests, outcomes, latency, regions, pool);
        }

        /** The smallest bucket that contains at least the fraction of all requests. */
        private long percentile(double fraction) {
            long rank = (long) Math.ceil(fraction * requests);
            long seen = 0;
            int bucket = 0;
            while (seen + latencyBuckets[bucket] < rank) {
                seen += latencyBuckets[bucket];
                bucket++;
            }
            return bucket;
        }
    }

    public record Report(String scope, double seconds, long requests, Map<Outcome, Long> outcomes,
                         Map<String, Long> latencyMilliseconds, Map<String, Long> regions, PoolStats pool) {
        public Report {
            outcomes = Collections.unmodifiableMap(
                    outcomes.isEmpty() ? new EnumMap<>(Outcome.class) : new EnumMap<>(outcomes));
            latencyMilliseconds = Collections.unmodifiableMap(new LinkedHashMap<>(latencyMilliseconds));
            regions = Collections.unmodifiableMap(new TreeMap<>(regions));
        }

        /** True when at least one read had an outcome other than {@link Outcome#OK}. */
        public boolean failed() {
            return outcomes.keySet().stream().anyMatch(outcome -> outcome != Outcome.OK);
        }

        public String summary() {
            double rate = seconds > 0 ? requests / seconds : 0;
            return String.format(Locale.ROOT, "Product reads, %s %.1f s: %d requests, %.1f/s, outcomes %s, "
                            + "latency ms %s, regions %s, pool {leased=%d, pending=%d, available=%d, max=%d}",
                    scope, seconds, requests, rate, braces(outcomes), braces(latencyMilliseconds), braces(regions),
                    pool.getLeased(), pool.getPending(), pool.getAvailable(), pool.getMax());
        }

        private static String braces(Map<?, Long> values) {
            StringJoiner text = new StringJoiner(", ", "{", "}");
            values.forEach((name, value) -> text.add(name + "=" + value));
            return text.toString();
        }
    }
}
