package dev.nklip.javacraft.shardshop.workload.reader.metrics;

import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult;
import dev.nklip.javacraft.shardshop.workload.control.RatePacer.StartTiming;
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
    private final boolean diagnosticsEnabled;
    private Window interval;
    private Window total;
    private long intervalSequence;

    public ReaderMetrics(LongSupplier nanoTime) {
        this(nanoTime, false);
    }

    public ReaderMetrics(LongSupplier nanoTime, boolean diagnosticsEnabled) {
        this.diagnosticsEnabled = diagnosticsEnabled;
        this.nanoTime = Objects.requireNonNull(nanoTime);
        long now = nanoTime.getAsLong();
        this.interval = new Window(now, diagnosticsEnabled);
        this.total = new Window(now, diagnosticsEnabled);
    }

    public boolean diagnosticsEnabled() {
        return diagnosticsEnabled;
    }

    /** Records a completed read's granted permit and the timestamp immediately before requests.get. */
    public synchronized void recordScheduling(StartTiming timing, long dispatchNanos) {
        if (diagnosticsEnabled) {
            interval.scheduling.add(timing, dispatchNanos);
            total.scheduling.add(timing, dispatchNanos);
        }
    }

    /** Starts the interval and the totals now, when the load starts. */
    public synchronized void begin() {
        long now = nanoTime.getAsLong();
        interval = new Window(now, diagnosticsEnabled);
        total = new Window(now, diagnosticsEnabled);
        intervalSequence = 0;
    }

    public synchronized void record(String region, ReadResult<?> result, long latencyNanos) {
        long milliseconds = Math.max(0, (latencyNanos + 999_999) / 1_000_000);
        interval.add(region, result, milliseconds);
        total.add(region, result, milliseconds);
    }

    /** Records scheduling and result in the same report window; interrupted reads have no completed result. */
    public synchronized void record(String region, ReadResult<?> result, long latencyNanos,
                                    StartTiming timing, long dispatchNanos) {
        record(region, result, latencyNanos);
        recordScheduling(timing, dispatchNanos);
    }

    /** Returns the interval since the previous call, with the current pool state, and starts a new interval. */
    public synchronized Report nextInterval(PoolStats pool) {
        long now = nanoTime.getAsLong();
        Report report = interval.report("interval", now, pool, ++intervalSequence);
        interval = new Window(now, diagnosticsEnabled);
        return report;
    }

    public synchronized Report total(PoolStats pool) {
        return total.report("total", nanoTime.getAsLong(), pool, 0);
    }

    private static final class Window {
        private final long start;
        private final long[] latencyBuckets = new long[MAX_BUCKET_MS + 1];
        private final Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);
        private final Map<String, Long> regions = new TreeMap<>();
        private final Scheduling scheduling;
        private long requests;
        private long maxMilliseconds;

        private Window(long start, boolean diagnosticsEnabled) {
            this.start = start;
            scheduling = diagnosticsEnabled ? new Scheduling() : null;
        }

        private void add(String region, ReadResult<?> result, long milliseconds) {
            requests++;
            outcomes.merge(result.outcome(), 1L, Long::sum);
            regions.merge(region, 1L, Long::sum);
            latencyBuckets[(int) Math.min(MAX_BUCKET_MS, milliseconds)]++;
            maxMilliseconds = Math.max(maxMilliseconds, milliseconds);
        }

        private Report report(String scope, long now, PoolStats pool, long sequence) {
            Map<String, Long> latency = new LinkedHashMap<>();
            if (requests > 0) {
                latency.put("p50", percentile(0.50));
                latency.put("p95", percentile(0.95));
                latency.put("p99", percentile(0.99));
                latency.put("max", maxMilliseconds);
            }
            return new Report(scope, (now - start) / 1e9, requests, outcomes, latency, regions, pool,
                    scheduling == null ? Map.of() : scheduling.report(), start, now, sequence);
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

    private static final class Scheduling {
        private final TimingHistogram wake = new TimingHistogram();
        private final TimingHistogram lateArrival = new TimingHistogram();
        private final TimingHistogram dispatch = new TimingHistogram();

        private void add(StartTiming timing, long dispatchNanos) {
            wake.add(timing.wakeDelayNanos());
            if (timing.lateArrivalNanos() >= 0) {
                lateArrival.add(timing.lateArrivalNanos());
            }
            dispatch.add(Math.max(0, dispatchNanos - timing.permitNanos()));
        }

        private Map<String, Timing> report() {
            Map<String, Timing> result = new LinkedHashMap<>();
            result.put("wake", wake.report());
            result.put("lateArrival", lateArrival.report());
            result.put("dispatch", dispatch.report());
            return result;
        }
    }

    /**
     * Fixed memory: exact whole-microsecond buckets below 128 us, then 64 subdivisions per power of two.
     * Percentiles are nearest-rank upper bucket bounds (at most about 1.6% bucket width), never capped.
     * Mean and maximum use the original nanoseconds. No request samples are retained.
     */
    private static final class TimingHistogram {
        private final long[] buckets = new long[4096];
        private long count;
        private double sumNanos;
        private long maxNanos;

        private void add(long nanos) {
            long micros = nanos / 1_000 + (nanos % 1_000 == 0 ? 0 : 1);
            int index;
            if (micros < 64) {
                index = (int) micros;
            } else {
                int exponent = 63 - Long.numberOfLeadingZeros(micros);
                int shift = exponent - 6;
                index = 64 + shift * 64 + (int) ((micros >>> shift) - 64);
            }
            buckets[index]++;
            count++;
            sumNanos += nanos;
            maxNanos = Math.max(maxNanos, nanos);
        }

        private Timing report() {
            return new Timing(count, count == 0 ? 0 : sumNanos / count / 1_000,
                    percentile(0.50), percentile(0.95), percentile(0.99), maxNanos / 1_000.0);
        }

        private long percentile(double fraction) {
            if (count == 0) {
                return 0;
            }
            long rank = (long) Math.ceil(count * fraction);
            long seen = 0;
            for (int bucket = 0; bucket < buckets.length; bucket++) {
                seen += buckets[bucket];
                if (seen >= rank) {
                    if (bucket < 64) {
                        return bucket;
                    }
                    int shift = (bucket - 64) / 64;
                    long base = 64 + (bucket - 64) % 64;
                    return ((base + 1) << shift) - 1;
                }
            }
            throw new IllegalStateException("Missing timing histogram sample");
        }
    }

    /** Count and timing statistics in microseconds; count zero means no observations. */
    public record Timing(long count, double mean, long p50, long p95, long p99, double max) {
        private String summary() {
            return String.format(Locale.ROOT, "{count=%d, mean=%.3f, p50=%d, p95=%d, p99=%d, max=%.3f}",
                    count, mean, p50, p95, p99, max);
        }
    }

    public record Report(String scope, double seconds, long requests, Map<Outcome, Long> outcomes,
                         Map<String, Long> latencyMilliseconds, Map<String, Long> regions, PoolStats pool,
                         Map<String, Timing> schedulingMicroseconds, long startNanos, long endNanos, long sequence) {
        public Report {
            outcomes = Collections.unmodifiableMap(
                    outcomes.isEmpty() ? new EnumMap<>(Outcome.class) : new EnumMap<>(outcomes));
            latencyMilliseconds = Collections.unmodifiableMap(new LinkedHashMap<>(latencyMilliseconds));
            regions = Collections.unmodifiableMap(new TreeMap<>(regions));
            schedulingMicroseconds = Collections.unmodifiableMap(new LinkedHashMap<>(schedulingMicroseconds));
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

        /** Appended by the reader after the existing catalog digest, so existing fields keep their positions. */
        public String schedulingSummary() {
            if (schedulingMicroseconds.isEmpty()) {
                return "";
            }
            StringJoiner text = new StringJoiner(", ", ", scheduling us {", "}");
            schedulingMicroseconds.forEach((name, timing) -> text.add(name + "=" + timing.summary()));
            return text + ", window={sequence=" + sequence + ", startNanos=" + startNanos
                    + ", endNanos=" + endNanos + "}";
        }

        private static String braces(Map<?, Long> values) {
            StringJoiner text = new StringJoiner(", ", "{", "}");
            values.forEach((name, value) -> text.add(name + "=" + value));
            return text.toString();
        }
    }
}
