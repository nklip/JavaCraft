package dev.nklip.javacraft.echo.blocking.step;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import dev.nklip.javacraft.echo.blocking.client.platform.PlatformThreadClient;
import dev.nklip.javacraft.echo.blocking.client.virtual.VirtualThreadClient;
import org.junit.jupiter.api.Assertions;

public class BlockingBenchmarkStepDefinitions {

    private static final Object SYSTEM_OUT_LOCK = new Object();
    private static final Path PERFORMANCE_RESULTS_DIR = Path.of("target", "performance-results");

    private final BlockingStepDefinitions blockingSteps = new BlockingStepDefinitions();

    @When("performance benchmark for {word} server and {word} client runs {int} warmups and {int} measured runs with {int} clients and {int} messages on port {int}")
    public void runThreadPerformanceBenchmark(
            String serverType,
            String clientType,
            int warmups,
            int measuredRuns,
            int clientCount,
            int messagesPerClient,
            int port) {

        Assertions.assertTrue(warmups >= 0, "Warmups must be zero or greater");
        Assertions.assertTrue(measuredRuns > 0, "Measured runs must be greater than zero");
        Assertions.assertTrue(clientCount > 0, "Client count must be greater than zero");
        Assertions.assertTrue(messagesPerClient > 0, "Messages per client must be greater than zero");

        String normalizedServerType = normalizeThreadType(serverType, "server");
        String normalizedClientType = normalizeThreadType(clientType, "client");
        String summaryKey = benchmarkKey(normalizedServerType, normalizedClientType);
        String benchmarkLabel = benchmarkLabel(normalizedServerType, normalizedClientType);

        PrintStream benchmarkOut = System.out;
        long benchmarkStartedAt = System.nanoTime();
        int totalMessages = clientCount * messagesPerClient;
        List<Long> measuredRunNanos = new ArrayList<>(measuredRuns);

        for (int warmup = 1; warmup <= warmups; warmup++) {
            String prefix = "%s%sPerfWarmup%02d".formatted(
                    capitalize(normalizedServerType),
                    capitalize(normalizedClientType),
                    warmup
            );
            long elapsedNanos = runSingleBenchmarkIteration(
                    normalizedServerType,
                    normalizedClientType,
                    clientCount,
                    messagesPerClient,
                    port,
                    prefix
            );
            printRunMetrics(benchmarkOut, benchmarkLabel, "WARMUP", warmup, warmups,
                    clientCount, messagesPerClient, totalMessages, elapsedNanos);
        }

        for (int run = 1; run <= measuredRuns; run++) {
            String prefix = "%s%sPerfRun%02d".formatted(
                    capitalize(normalizedServerType),
                    capitalize(normalizedClientType),
                    run
            );
            long elapsedNanos = runSingleBenchmarkIteration(
                    normalizedServerType,
                    normalizedClientType,
                    clientCount,
                    messagesPerClient,
                    port,
                    prefix
            );
            measuredRunNanos.add(elapsedNanos);
            printRunMetrics(benchmarkOut, benchmarkLabel, null, run, measuredRuns,
                    clientCount, messagesPerClient, totalMessages, elapsedNanos);
        }

        long benchmarkElapsedNanos = System.nanoTime() - benchmarkStartedAt;
        long measuredTotalNanos = measuredRunNanos.stream().mapToLong(Long::longValue).sum();
        double avgNanos = measuredTotalNanos / (double) measuredRuns;
        long medianNanos = medianNanos(measuredRunNanos);

        printAggregateMetrics(benchmarkOut, benchmarkLabel, measuredRuns,
                clientCount, messagesPerClient, totalMessages, avgNanos, medianNanos);

        writePerformanceSummary(
                summaryKey,
                normalizedServerType,
                normalizedClientType,
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

    @Then("performance averages for all server - client combinations are compared and total execution time is printed")
    public void comparePerformanceAveragesFromSeparateRuns() {
        List<PerformanceSummary> summaries = List.of(
                readPerformanceSummary(benchmarkKey("platform", "platform")),
                readPerformanceSummary(benchmarkKey("platform", "virtual")),
                readPerformanceSummary(benchmarkKey("virtual", "platform")),
                readPerformanceSummary(benchmarkKey("virtual", "virtual"))
        );

        PerformanceSummary first = summaries.getFirst();
        for (PerformanceSummary summary : summaries) {
            Assertions.assertEquals(first.clientCount(), summary.clientCount(),
                    "Client counts differ between performance scenarios");
            Assertions.assertEquals(first.messagesPerClient(), summary.messagesPerClient(),
                    "Messages-per-client differ between performance scenarios");
        }

        List<PerformanceSummary> sortedByAverage = summaries.stream()
                .sorted(Comparator.comparingDouble(PerformanceSummary::averageNanos))
                .toList();

        PerformanceSummary fastest = sortedByAverage.getFirst();

        PrintStream out = System.out;
        out.println("[PERF][FINAL] average execution time sorted (fastest -> slowest):");
        for (int i = 0; i < sortedByAverage.size(); i++) {
            PerformanceSummary summary = sortedByAverage.get(i);
            double deltaNanos = summary.averageNanos() - fastest.averageNanos();
            double deltaPercent = fastest.averageNanos() == 0.0
                    ? 0.0
                    : (deltaNanos * 100.0) / fastest.averageNanos();

            out.printf(
                    "[PERF][FINAL] %d) %s average: %.3f s, throughput=%.2f msg/s, avg=%.4f ms/msg, delta=%.3f s (%+.2f%% vs fastest)%n",
                    i + 1,
                    summary.benchmarkLabel(),
                    nanosToSeconds(summary.averageNanos()),
                    throughput(summary.totalMessages(), summary.averageNanos()),
                    avgLatencyMillis(summary.totalMessages(), summary.averageNanos()),
                    nanosToSeconds(deltaNanos),
                    deltaPercent
            );
        }

        long combinedMeasuredNanos = summaries.stream().mapToLong(PerformanceSummary::measuredTotalNanos).sum();
        long combinedScenarioNanos = summaries.stream().mapToLong(PerformanceSummary::scenarioElapsedNanos).sum();

        out.printf(
                "[PERF][FINAL] fastest average scenario: %s (%.3f s)%n",
                fastest.benchmarkLabel(),
                nanosToSeconds(fastest.averageNanos())
        );
        out.printf(
                "[PERF][FINAL] total measured execution time: %.3f s (all 4 tests)%n",
                nanosToSeconds(combinedMeasuredNanos)
        );
        out.printf(
                "[PERF][FINAL] total benchmark scenario time (warmups + measured): %.3f s (all 4 tests)%n",
                nanosToSeconds(combinedScenarioNanos)
        );
    }

    private long runSingleBenchmarkIteration(
            String normalizedServerType,
            String normalizedClientType,
            int clientCount,
            int messagesPerClient,
            int port,
            String prefix) {
        return withSuppressedSystemOut(() -> {
            if ("platform".equals(normalizedServerType)) {
                return runSequentialBenchmarkIteration(
                        normalizedClientType,
                        clientCount,
                        messagesPerClient,
                        port,
                        prefix
                );
            }
            return runConcurrentBenchmarkIteration(
                    normalizedClientType,
                    clientCount,
                    messagesPerClient,
                    port,
                    prefix
            );
        });
    }

    private long runConcurrentBenchmarkIteration(
            String normalizedClientType,
            int clientCount,
            int messagesPerClient,
            int port,
            String prefix) {
        connectBenchmarkClients(normalizedClientType, clientCount, prefix, port);

        awaitExpectedConnectedClients(normalizedClientType, clientName(prefix, 1), clientCount);

        long startedAt = System.nanoTime();
        blockingSteps.clientsEachSendEchoMessagesWithRandomDelay(clientCount, prefix, messagesPerClient, 0, 0);
        long elapsedNanos = System.nanoTime() - startedAt;

        blockingSteps.clientsWithPrefixDisconnectWithGoodbye(clientCount, prefix);
        return elapsedNanos;
    }

    /**
     * ServerThread increments the connected-clients counter in each client thread run() method.
     * Right after TCP connect, one or more threads may not have executed increment yet.
     * For benchmark determinism we poll "stats" until the expected count is observed.
     */
    private void awaitExpectedConnectedClients(String normalizedClientType, String clientName, int expectedClients) {
        String expectedResponse = "Simultaneously connected clients: %d".formatted(expectedClients);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String lastActual = null;

        while (System.nanoTime() < deadline) {
            lastActual = requestStats(normalizedClientType, clientName);
            if (expectedResponse.equals(lastActual)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }

        Assertions.fail(
                "Expected stats '%s' but got '%s' for client '%s'".formatted(
                        expectedResponse,
                        String.valueOf(lastActual),
                        clientName
                )
        );
    }

    private String requestStats(String normalizedClientType, String clientName) {
        if ("virtual".equals(normalizedClientType)) {
            VirtualThreadClient client = CommonStepDefinitions.virtualConnections().get(clientName);
            Assertions.assertNotNull(client, "Client '%s' not found".formatted(clientName));
            client.sendMessage("stats");
            return client.readMessage();
        }

        PlatformThreadClient client = CommonStepDefinitions.platformConnections().get(clientName);
        Assertions.assertNotNull(client, "Client '%s' not found".formatted(clientName));
        client.sendMessage("stats");
        return client.readMessage();
    }

    private long runSequentialBenchmarkIteration(
            String normalizedClientType,
            int clientCount,
            int messagesPerClient,
            int port,
            String prefix) {
        long startedAt = System.nanoTime();
        for (int i = 1; i <= clientCount; i++) {
            String currentClientName = clientName(prefix, i);
            connectBenchmarkClient(normalizedClientType, currentClientName, port);

            blockingSteps.sendMessage(
                    currentClientName,
                    "stats",
                    "Simultaneously connected clients: 1"
            );
            for (int messageIndex = 1; messageIndex <= messagesPerClient; messageIndex++) {
                String message = "%s message %03d".formatted(currentClientName, messageIndex);
                blockingSteps.sendMessage(currentClientName, message, "Did you say '%s'?".formatted(message));
            }
            blockingSteps.disconnectClientWithGoodbye(currentClientName);
        }
        return System.nanoTime() - startedAt;
    }

    private void connectBenchmarkClients(String normalizedClientType, int clientCount, String prefix, int port) {
        for (int i = 1; i <= clientCount; i++) {
            String currentClientName = clientName(prefix, i);
            connectBenchmarkClient(normalizedClientType, currentClientName, port);
        }
    }

    private void connectBenchmarkClient(String normalizedClientType, String clientName, int port) {
        if ("virtual".equals(normalizedClientType)) {
            blockingSteps.connectVirtualClient(clientName, port);
        } else {
            blockingSteps.connectPlatformClient(clientName, port);
        }
    }

    private String normalizeThreadType(String value, String role) {
        String normalized = value.toLowerCase();
        Assertions.assertTrue(
                "virtual".equals(normalized) || "platform".equals(normalized),
                "Unsupported %s type: %s".formatted(role, value)
        );
        return normalized;
    }

    private String benchmarkKey(String serverType, String clientType) {
        return "%s-%s".formatted(serverType, clientType);
    }

    private String benchmarkLabel(String serverType, String clientType) {
        return "%s_SERVER+%s_CLIENT".formatted(serverType.toUpperCase(), clientType.toUpperCase());
    }

    private String capitalize(String value) {
        return value.substring(0, 1).toUpperCase() + value.substring(1);
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
            String benchmarkLabel,
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
                benchmarkLabel,
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
            String benchmarkLabel,
            int measuredRuns,
            int clientCount,
            int messagesPerClient,
            int totalMessages,
            double avgNanos,
            long medianNanos) {
        out.printf(
                "[PERF][%s] average over %d measured runs: clients=%d, messages/client=%d, total=%d, elapsed=%.3f s, throughput=%.2f msg/s, avg=%.4f ms/msg%n",
                benchmarkLabel,
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
                benchmarkLabel,
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
            String summaryKey,
            String serverType,
            String clientType,
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
        properties.setProperty("summaryKey", summaryKey);
        properties.setProperty("serverType", serverType);
        properties.setProperty("clientType", clientType);
        properties.setProperty("benchmarkLabel", benchmarkLabel(serverType, clientType));
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
            Path summaryFile = performanceSummaryFile(summaryKey);
            try (var writer = Files.newBufferedWriter(summaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Performance benchmark summary for " + summaryKey);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist benchmark results for " + summaryKey, e);
        }
    }

    private PerformanceSummary readPerformanceSummary(String summaryKey) {
        Path summaryFile = performanceSummaryFile(summaryKey);
        Assertions.assertTrue(Files.exists(summaryFile),
                "No persisted benchmark summary for '%s'. Run the matching '@Performance' scenario first."
                        .formatted(summaryKey));

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(summaryFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read benchmark summary for " + summaryKey, e);
        }

        String storedServerType = properties.getProperty("serverType");
        String storedClientType = properties.getProperty("clientType");
        String fallbackLabel = storedServerType != null && storedClientType != null
                ? benchmarkLabel(storedServerType, storedClientType)
                : summaryKey.toUpperCase();

        return new PerformanceSummary(
                properties.getProperty("summaryKey", summaryKey),
                properties.getProperty("benchmarkLabel", fallbackLabel),
                Integer.parseInt(properties.getProperty("clientCount")),
                Integer.parseInt(properties.getProperty("messagesPerClient")),
                Integer.parseInt(properties.getProperty("totalMessages")),
                Long.parseLong(properties.getProperty("measuredTotalNanos")),
                Long.parseLong(properties.getProperty("medianNanos")),
                Double.parseDouble(properties.getProperty("averageNanos")),
                Long.parseLong(properties.getProperty("scenarioElapsedNanos"))
        );
    }

    private Path performanceSummaryFile(String summaryKey) {
        return PERFORMANCE_RESULTS_DIR.resolve(summaryKey + ".properties");
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

    private String clientName(String prefix, int index) {
        return "%s-%03d".formatted(prefix, index);
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
