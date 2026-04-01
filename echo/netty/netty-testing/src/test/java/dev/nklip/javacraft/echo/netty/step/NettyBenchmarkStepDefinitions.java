package dev.nklip.javacraft.echo.netty.step;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assertions;

public class NettyBenchmarkStepDefinitions {

    private static final Object SYSTEM_OUT_LOCK = new Object();
    private static final Path PERFORMANCE_RESULTS_DIR = Path.of("target", "performance-results");
    private static final String SUMMARY_KEY = "netty-netty";
    private static final String BENCHMARK_LABEL = "NETTY_SERVER+NETTY_CLIENT";
    private static final long STATS_RETRY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long STATS_RETRY_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final NettyStepDefinitions nettySteps = new NettyStepDefinitions();

    @When("performance benchmark for netty server and netty client runs {int} warmups and {int} measured runs with {int} clients and {int} messages on port {int}")
    public void runNettyPerformanceBenchmark(
            int warmups,
            int measuredRuns,
            int clientCount,
            int messagesPerClient,
            int port) {
        Assertions.assertTrue(warmups >= 0, "Warmups must be zero or greater");
        Assertions.assertTrue(measuredRuns > 0, "Measured runs must be greater than zero");
        Assertions.assertTrue(clientCount > 0, "Client count must be greater than zero");
        Assertions.assertTrue(messagesPerClient > 0, "Messages per client must be greater than zero");

        PrintStream benchmarkOut = System.out;
        long benchmarkStartedAt = System.nanoTime();
        int totalMessages = clientCount * messagesPerClient;
        List<Long> measuredRunNanos = new ArrayList<>(measuredRuns);

        for (int warmup = 1; warmup <= warmups; warmup++) {
            String prefix = "NettyPerfWarmup%02d".formatted(warmup);
            long elapsedNanos = runSingleBenchmarkIteration(clientCount, messagesPerClient, port, prefix);
            printRunMetrics(benchmarkOut, "WARMUP", warmup, warmups,
                    clientCount, messagesPerClient, totalMessages, elapsedNanos);
        }

        for (int run = 1; run <= measuredRuns; run++) {
            String prefix = "NettyPerfRun%02d".formatted(run);
            long elapsedNanos = runSingleBenchmarkIteration(clientCount, messagesPerClient, port, prefix);
            measuredRunNanos.add(elapsedNanos);
            printRunMetrics(benchmarkOut, null, run, measuredRuns,
                    clientCount, messagesPerClient, totalMessages, elapsedNanos);
        }

        long benchmarkElapsedNanos = System.nanoTime() - benchmarkStartedAt;
        long measuredTotalNanos = measuredRunNanos.stream().mapToLong(Long::longValue).sum();
        double avgNanos = measuredTotalNanos / (double) measuredRuns;
        long medianNanos = medianNanos(measuredRunNanos);

        printAggregateMetrics(benchmarkOut, measuredRuns,
                clientCount, messagesPerClient, totalMessages, avgNanos, medianNanos);

        writePerformanceSummary(
                warmups,
                measuredRuns,
                clientCount,
                messagesPerClient,
                totalMessages,
                measuredTotalNanos,
                medianNanos,
                avgNanos,
                benchmarkElapsedNanos
        );
    }

    @Then("performance average for netty server and netty client is printed with total execution time")
    public void compareNettyBenchmarkAverage() {
        PerformanceSummary summary = readPerformanceSummary();

        PrintStream out = System.out;
        out.println("[PERF][FINAL] average execution time for netty benchmark case:");
        out.printf(
                "[PERF][FINAL] 1) %s average: %.3f s, throughput=%.2f msg/s, avg=%.4f ms/msg, delta=0.000 s (+0.00%% vs fastest)%n",
                summary.benchmarkLabel(),
                nanosToSeconds(summary.averageNanos()),
                throughput(summary.totalMessages(), summary.averageNanos()),
                avgLatencyMillis(summary.totalMessages(), summary.averageNanos())
        );
        out.printf(
                "[PERF][FINAL] total measured execution time: %.3f s (single test)%n",
                nanosToSeconds(summary.measuredTotalNanos())
        );
        out.printf(
                "[PERF][FINAL] total benchmark scenario time (warmups + measured): %.3f s (single test)%n",
                nanosToSeconds(summary.scenarioElapsedNanos())
        );
    }

    private long runSingleBenchmarkIteration(
            int clientCount,
            int messagesPerClient,
            int port,
            String prefix) {
        return withSuppressedSystemOut(() -> {
            try {
                nettySteps.connectClientsWithPrefix(clientCount, prefix, port);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Failed to connect benchmark clients", e);
            }

            waitForExpectedStats(clientCount, prefix);

            long startedAt = System.nanoTime();
            nettySteps.clientsWithPrefixEachSendEchoMessagesFromTheirOwnThreadWithRandomDelay(
                    clientCount,
                    prefix,
                    messagesPerClient,
                    0,
                    0
            );
            long elapsedNanos = System.nanoTime() - startedAt;

            nettySteps.clientsWithPrefixDisconnectWithGoodbye(clientCount, prefix);
            waitForAllClientsToClose(clientCount, prefix);
            return elapsedNanos;
        });
    }

    private void waitForExpectedStats(int clientCount, String prefix) {
        String clientName = benchmarkFirstClientName(prefix);
        String expectedStats = "Simultaneously connected clients: %d".formatted(clientCount);
        long deadline = System.nanoTime() + STATS_RETRY_TIMEOUT_NANOS;
        AssertionError lastFailure = null;

        while (System.nanoTime() < deadline) {
            try {
                nettySteps.sendMessage(clientName, "stats", expectedStats);
                return;
            } catch (AssertionError assertionError) {
                lastFailure = assertionError;
                LockSupport.parkNanos(STATS_RETRY_DELAY_NANOS);
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        Assertions.fail("Failed to validate connected client stats for prefix '%s'".formatted(prefix));
    }

    private void waitForAllClientsToClose(int clientCount, String prefix) {
        for (int clientIndex = 1; clientIndex <= clientCount; clientIndex++) {
            String clientName = "%s-%03d".formatted(prefix, clientIndex);
            try {
                nettySteps.clientSocketIsClosed(clientName);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Client '%s' socket was expected to close after goodbye".formatted(clientName), e);
            }
        }
    }

    private String benchmarkFirstClientName(String prefix) {
        return "%s-%03d".formatted(prefix, 1);
    }

    private long medianNanos(List<Long> measuredRunNanos) {
        List<Long> sorted = measuredRunNanos.stream().sorted(Comparator.naturalOrder()).toList();
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        long left = sorted.get((size / 2) - 1);
        long right = sorted.get(size / 2);
        return (left + right) / 2;
    }

    private void printRunMetrics(
            PrintStream out,
            String runType,
            int run,
            int totalRuns,
            int clientCount,
            int messagesPerClient,
            int totalMessages,
            long elapsedNanos) {
        String runTypeSuffix = runType == null ? "" : "[" + runType + "]";
        out.printf(
                "[PERF][%s]%s run %d/%d: clients=%d, messages/client=%d, total=%d, elapsed=%.3f s, throughput=%.2f msg/s, avg=%.4f ms/msg%n",
                BENCHMARK_LABEL,
                runTypeSuffix,
                run,
                totalRuns,
                clientCount,
                messagesPerClient,
                totalMessages,
                nanosToSeconds(elapsedNanos),
                throughput(totalMessages, elapsedNanos),
                avgLatencyMillis(totalMessages, elapsedNanos)
        );
    }

    private void printAggregateMetrics(
            PrintStream out,
            int measuredRuns,
            int clientCount,
            int messagesPerClient,
            int totalMessages,
            double avgNanos,
            long medianNanos) {
        out.printf(
                "[PERF][%s] average over %d measured runs: clients=%d, messages/client=%d, total=%d, elapsed=%.3f s, throughput=%.2f msg/s, avg=%.4f ms/msg%n",
                BENCHMARK_LABEL,
                measuredRuns,
                clientCount,
                messagesPerClient,
                totalMessages,
                nanosToSeconds(avgNanos),
                throughput(totalMessages, avgNanos),
                avgLatencyMillis(totalMessages, avgNanos)
        );
        out.printf(
                "[PERF][%s] median over %d measured runs: clients=%d, messages/client=%d, total=%d, elapsed=%.3f s, throughput=%.2f msg/s, avg=%.4f ms/msg%n",
                BENCHMARK_LABEL,
                measuredRuns,
                clientCount,
                messagesPerClient,
                totalMessages,
                nanosToSeconds(medianNanos),
                throughput(totalMessages, medianNanos),
                avgLatencyMillis(totalMessages, medianNanos)
        );
    }

    private void writePerformanceSummary(
            int warmups,
            int measuredRuns,
            int clientCount,
            int messagesPerClient,
            int totalMessages,
            long measuredTotalNanos,
            long medianNanos,
            double averageNanos,
            long scenarioElapsedNanos) {
        Properties properties = new Properties();
        properties.setProperty("summaryKey", SUMMARY_KEY);
        properties.setProperty("benchmarkLabel", BENCHMARK_LABEL);
        properties.setProperty("warmups", Integer.toString(warmups));
        properties.setProperty("measuredRuns", Integer.toString(measuredRuns));
        properties.setProperty("clientCount", Integer.toString(clientCount));
        properties.setProperty("messagesPerClient", Integer.toString(messagesPerClient));
        properties.setProperty("totalMessages", Integer.toString(totalMessages));
        properties.setProperty("measuredTotalNanos", Long.toString(measuredTotalNanos));
        properties.setProperty("medianNanos", Long.toString(medianNanos));
        properties.setProperty("averageNanos", Double.toString(averageNanos));
        properties.setProperty("scenarioElapsedNanos", Long.toString(scenarioElapsedNanos));

        try {
            Files.createDirectories(PERFORMANCE_RESULTS_DIR);
            Path summaryFile = performanceSummaryFile();
            try (var writer = Files.newBufferedWriter(summaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Performance benchmark summary for " + SUMMARY_KEY);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist benchmark results for " + SUMMARY_KEY, e);
        }
    }

    private PerformanceSummary readPerformanceSummary() {
        Path summaryFile = performanceSummaryFile();
        Assertions.assertTrue(Files.exists(summaryFile),
                "No persisted benchmark summary for '%s'. Run the matching '@Performance' scenario first."
                        .formatted(SUMMARY_KEY));

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(summaryFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read benchmark summary for " + SUMMARY_KEY, e);
        }

        return new PerformanceSummary(
                properties.getProperty("summaryKey", SUMMARY_KEY),
                properties.getProperty("benchmarkLabel", BENCHMARK_LABEL),
                Integer.parseInt(properties.getProperty("clientCount")),
                Integer.parseInt(properties.getProperty("messagesPerClient")),
                Integer.parseInt(properties.getProperty("totalMessages")),
                Long.parseLong(properties.getProperty("measuredTotalNanos")),
                Long.parseLong(properties.getProperty("medianNanos")),
                Double.parseDouble(properties.getProperty("averageNanos")),
                Long.parseLong(properties.getProperty("scenarioElapsedNanos"))
        );
    }

    private Path performanceSummaryFile() {
        return PERFORMANCE_RESULTS_DIR.resolve(SUMMARY_KEY + ".properties");
    }

    private double nanosToSeconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private double nanosToSeconds(double nanos) {
        return nanos / 1_000_000_000.0;
    }

    private double throughput(int totalMessages, long elapsedNanos) {
        return throughput(totalMessages, (double) elapsedNanos);
    }

    private double throughput(int totalMessages, double elapsedNanos) {
        return totalMessages / (elapsedNanos / 1_000_000_000.0);
    }

    private double avgLatencyMillis(int totalMessages, long elapsedNanos) {
        return avgLatencyMillis(totalMessages, (double) elapsedNanos);
    }

    private double avgLatencyMillis(int totalMessages, double elapsedNanos) {
        return (elapsedNanos / 1_000_000.0) / totalMessages;
    }

    private <T> T withSuppressedSystemOut(Supplier<T> supplier) {
        synchronized (SYSTEM_OUT_LOCK) {
            PrintStream originalOut = System.out;
            try (PrintStream suppressedOut =
                         new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)) {
                System.setOut(suppressedOut);
                return supplier.get();
            } finally {
                System.setOut(originalOut);
            }
        }
    }

    private record PerformanceSummary(
            String summaryKey,
            String benchmarkLabel,
            int clientCount,
            int messagesPerClient,
            int totalMessages,
            long measuredTotalNanos,
            long medianNanos,
            double averageNanos,
            long scenarioElapsedNanos) {
    }
}
