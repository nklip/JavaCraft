package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.stock.StockCoordinator;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static dev.nklip.javacraft.shardshop.order.stock.StockCoordinator.Progress.IDLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockWorkerSchedulingTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unexpectedFailureIsVisibleAndDoesNotPermanentlyStopScheduledWork(int concurrency) {
        var topology = ShardTopology.fromNamesAndRegions("a,b", "US,EU");
        var failed = topology.shards().getFirst();
        var healthy = topology.shards().getLast();
        StockCoordinator coordinator = mock();
        when(coordinator.processNext(failed)).thenThrow(new AssertionError("private payload\n".repeat(100)))
                .thenReturn(IDLE);
        when(coordinator.processNext(healthy)).thenReturn(IDLE);
        var clock = new ClockedScheduler();
        List<String> warnings = new ArrayList<>();
        Logger log = Logger.getLogger(StockWorker.class.getName());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(new SimpleFormatter().formatMessage(record));
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        boolean parentHandlers = log.getUseParentHandlers();
        log.addHandler(capture);
        log.setUseParentHandlers(false);
        try (var worker = new StockWorker(coordinator, topology,
                new StockWorker.Settings(true, Duration.ofMillis(250), Duration.ofSeconds(30), 2, concurrency),
                ignored -> clock.executor)) {
            worker.start();
            for (int turn = 0; turn < concurrency; turn++) {
                clock.next();
            }
            assertEquals(concurrency, clock.turns.size(), "A failed shard must retain its next scheduled attempt");
            assertTrue(clock.turns.stream().allMatch(turn -> turn.due() == 250),
                    "Unexpected failures must use the idle retry delay");
            for (int turn = 0; turn < concurrency; turn++) {
                clock.next();
            }
            verify(coordinator, times(2)).processNext(failed);
            verify(coordinator, times(2)).processNext(healthy);
            assertTrue(warnings.stream().anyMatch(line -> line.contains("failure for a (AssertionError)")),
                    warnings::toString);
            assertTrue(warnings.stream().allMatch(line -> line.length() < 300 && !line.contains("private payload")
                    && !line.contains("\n")), "Failure diagnostics must be bounded and exclude payload text");
        } finally {
            log.removeHandler(capture);
            log.setUseParentHandlers(parentHandlers);
        }
    }

    /** Models future failure capture and fixed-delay suppression, without real time or worker threads. */
    private static final class ClockedScheduler {
        private record Turn(Runnable task, long due, long sequence) { }
        private final ScheduledExecutorService executor = mock();
        private final PriorityQueue<Turn> turns = new PriorityQueue<>(
                Comparator.comparingLong(Turn::due).thenComparingLong(Turn::sequence));
        private long now;
        private long sequence;

        private ClockedScheduler() {
            when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
                TimeUnit unit = call.getArgument(2);
                enqueue(call.getArgument(0), unit.toMillis(call.getArgument(1)));
                return null;
            });
            when(executor.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(call -> {
                Runnable task = call.getArgument(0);
                TimeUnit unit = call.getArgument(3);
                long delay = unit.toMillis(call.getArgument(2));
                Runnable periodic = new Runnable() {
                    @Override
                    public void run() {
                        task.run();
                        enqueue(this, delay);
                    }
                };
                enqueue(periodic, unit.toMillis(call.getArgument(1)));
                return null;
            });
            try {
                when(executor.awaitTermination(anyLong(), any())).thenReturn(true);
            } catch (InterruptedException impossible) {
                throw new AssertionError(impossible);
            }
        }

        private void enqueue(Runnable task, long delay) {
            turns.add(new Turn(task, now + delay, sequence++));
        }

        private void next() {
            Turn turn = turns.remove();
            now = turn.due();
            // Scheduled executors capture task failures in their Future; failed periodic tasks do not run again.
            try {
                turn.task().run();
            } catch (AssertionError expectedTaskFailure) {
                // The queue and observable coordinator calls below determine whether the lane survived.
            }
        }
    }
}
