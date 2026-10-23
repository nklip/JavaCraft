package dev.nklip.javacraft.shardshop.order.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Arrays;
import java.util.Map;

/** Bounded per-JVM request measurements, including body reception, dispatch and response writing. */
@ApplicationScoped
@Path("/internal/order/write-metrics")
@Produces(MediaType.APPLICATION_JSON)
public class OrderWriteMetrics {
    private final Window allocation = new Window();
    private final Window put = new Window();

    Window operation(String method, String path) {
        if ("POST".equals(method) && path.matches("/api/v1/buyers/[^/]+/order-allocations")) {
            return allocation;
        }
        if ("PUT".equals(method) && path.matches("/api/v1/buyers/[^/]+/orders/[^/]+")) {
            return put;
        }
        return null;
    }

    @GET
    public Map<String, Snapshot> snapshot() {
        return Map.of("allocation", allocation.snapshot(), "put", put.snapshot());
    }

    public record Snapshot(long requests, long errors, int sampleSize, double p50Millis, double p95Millis,
                           double maxMillis) { }

    /** Last 4096 completed requests; totals live until this JVM exits. */
    static final class Window {
        private final long[] durations = new long[4096];
        private long requests;
        private long errors;

        synchronized void record(long nanos, int status) {
            durations[(int) (requests % durations.length)] = Math.max(0, nanos);
            requests++;
            if (status >= 400) {
                errors++;
            }
        }

        synchronized Snapshot snapshot() {
            int size = (int) Math.min(requests, durations.length);
            long[] sorted = Arrays.copyOf(durations, size);
            Arrays.sort(sorted);
            return new Snapshot(requests, errors, size, percentile(sorted, .5), percentile(sorted, .95),
                    size == 0 ? 0 : sorted[size - 1] / 1_000_000.0);
        }

        private static double percentile(long[] sorted, double quantile) {
            return sorted.length == 0 ? 0 : sorted[(int) Math.ceil(sorted.length * quantile) - 1] / 1_000_000.0;
        }
    }
}
