package dev.nklip.javacraft.shardshop.workload.control;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * The control and status view of one workload. {@code targetRate} is the operator's setting, which Pause keeps;
 * {@code effectiveRate} is zero while the workload is paused. Rates use {@code unit}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ControlStatus(String workload, String unit, String state, BigDecimal targetRate, boolean paused,
                            BigDecimal effectiveRate, BigDecimal defaultRate, BigDecimal maximumRate,
                            BigDecimal rateStep, BigDecimal achievedRate, long starts, int inFlight, long requests,
                            long errors, List<TrafficCategory> traffic, Limit limit, Run run, Producer producer, long revision) {
    public ControlStatus {
        traffic = List.copyOf(traffic);
    }

    /** Backend HTTP requests of one purpose since process start; retries and errors are parts of requests. */
    public record TrafficCategory(String category, long requests, long retries, long errors) { }

    /**
     * Present when the concurrency limit of the workload, with the mean duration of its recent operations, keeps the
     * achieved rate below the effective target.
     */
    public record Limit(String code, int concurrency, BigDecimal meanOperationSeconds, BigDecimal achievableRate) { }

    /** Producer-only measurements; other workloads return null. Latencies exclude recovered orders. */
    public record Producer(int submissionConcurrency, int observerConcurrency, int maxNonTerminalOrders,
                           int nonTerminalBacklog, boolean backpressured, long recoveredOrders,
                           long confirmedOrders, long cancelledOrders, Latency writeLatency, Latency sagaLatency) { }

    /** Milliseconds over the most recent 1,024 completions; samples counts all completions since process start. */
    public record Latency(long samples, BigDecimal meanMillis, BigDecimal p95Millis) { }

    /** One operator-started finite run; only the seeder has runs. Times are UTC ISO-8601 instants. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Run(long number, String state, String startedAt, String finishedAt, long completedOperations,
                      long plannedOperations, String summary, String error) { }
}
