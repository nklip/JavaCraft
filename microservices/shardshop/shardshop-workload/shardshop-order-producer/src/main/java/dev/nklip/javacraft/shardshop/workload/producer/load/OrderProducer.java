package dev.nklip.javacraft.shardshop.workload.producer.load;

import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.producer.CurrencySelector;
import dev.nklip.javacraft.shardshop.workload.producer.plan.OrderPlanner;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One ordered workload stream with one order in flight. Restart reconstructs ordinal zero and recovers every earlier
 * allocation; finished ordinals are not paced, thus the stream reaches new work after a few HTTP calls per finished
 * order. The live control paces the allocations of new orders and can pause them: the order in flight is observed
 * until it is terminal first.
 */
public final class OrderProducer {
    /** Upper bound of the target rate in new order starts per second. */
    public static final BigDecimal MAX_ORDER_RATE = new BigDecimal("100");
    private static final Logger LOG = Logger.getLogger(OrderProducer.class);
    private final OrderWorkload workload;
    private final Settings settings;
    private final WorkloadControl control;
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean stopped;
    private Thread controller;
    private ExecutorService phases;

    public OrderProducer(OrderWorkload workload, Settings settings, WorkloadControl control) {
        this.workload = Objects.requireNonNull(workload);
        this.settings = Objects.requireNonNull(settings);
        this.control = Objects.requireNonNull(control);
    }

    /** The control settings of the producer: new order starts per second, one order in flight. */
    public static WorkloadControl.Settings controlSettings(BigDecimal orderRate) {
        return new WorkloadControl.Settings("order-producer", "new order starts/s", orderRate, MAX_ORDER_RATE,
                new BigDecimal("0.1"), 1, List.of(OrderWorkload.STARTUP, OrderWorkload.ALLOCATION,
                OrderWorkload.SUBMISSION, OrderWorkload.POLLING));
    }

    public synchronized void start(Runnable onFailure) {
        Objects.requireNonNull(onFailure);
        if (controller != null) {
            throw new IllegalStateException("The order producer starts only once");
        }
        if (stopped) {
            return;
        }
        phases = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("order-producer-phase").factory());
        controller = Thread.ofVirtual().name("order-producer").start(() -> run(onFailure));
    }

    private void run(Runnable onFailure) {
        try {
            var products = timed("Catalog startup", settings.startupDeadline(), workload::resolveCatalog);
            var buyers = timed("Buyer creation", settings.startupDeadline(), workload::createBuyers);
            OrderPlanner planner = workload.planner(settings.runName(), settings.rejectedOrderPercent(), buyers, products);
            long sellerCount = products.stream().map(product -> product.sellerId()).distinct().count();
            LOG.infof("Order producer ready: sellers=%d products=%d buyers=%d", sellerCount, products.size(), buyers.size());
            control.phase(WorkloadControl.Phase.ACTIVE);
            long confirmed = 0;
            long cancelled = 0;
            long recovered = 0;
            boolean caughtUp = false;
            for (long ordinal = 0; !stopped; ordinal++) {
                long current = ordinal;
                // A replayed ordinal is not a new order, thus only Pause holds it. The first new order of a replay
                // did not wait either; it starts the paced schedule.
                boolean replaying = !caughtUp;
                if (replaying) {
                    control.awaitOpen();
                } else {
                    control.awaitStart();
                }
                var progress = new OrderWorkload.Progress(() -> {
                    control.started();
                    if (replaying) {
                        control.markStart();
                    }
                });
                OrderWorkload.Completion outcome;
                try (WorkloadControl.Operation ignored = control.begin()) {
                    outcome = timed("Order completion", settings.orderDeadline(),
                            () -> workload.complete(planner, settings.runName(), current, progress));
                } catch (ProducerException failure) {
                    throw new ProducerException(failure.getMessage() + " (" + progress.describe(current) + ")", failure);
                }
                if (outcome.recovered()) {
                    recovered++;
                } else if (outcome.status().status().equals("CONFIRMED")) {
                    confirmed++;
                } else {
                    cancelled++;
                }
                if (!outcome.recovered() && !caughtUp) {
                    caughtUp = true;
                    if (recovered > 0) {
                        LOG.infof("Order producer recovered %d finished orders before ordinal %d", recovered, current);
                    }
                }
                LOG.debugf("Order ordinal %d completed: %s", current, outcome.status().status());
                if (current % 100 == 0) {
                    LOG.infof("Order producer progress: confirmed=%d cancelled=%d recovered=%d",
                            confirmed, cancelled, recovered);
                }
                if (current == Long.MAX_VALUE) {
                    throw new ProducerException("Order request ordinals exhausted", null);
                }
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            if (!stopped) {
                fail("Order producer interrupted", onFailure);
            }
        } catch (ProducerException failure) {
            if (!stopped) {
                fail(failure.getMessage(), onFailure);
            }
        } catch (RuntimeException failure) {
            if (!stopped) {
                fail("Unexpected " + failure.getClass().getSimpleName(), onFailure);
            }
        } finally {
            phases.shutdownNow();
        }
    }

    private void fail(String reason, Runnable onFailure) {
        failed.set(true);
        control.phase(WorkloadControl.Phase.FAILED);
        LOG.error("Order producer failed: " + reason);
        onFailure.run();
    }

    private <T> T timed(String operation, Duration deadline, Callable<T> work)
            throws ProducerException, InterruptedException {
        Future<T> pending = phases.submit(work);
        try {
            return pending.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            throw new ProducerException(operation + " deadline exceeded", failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ProducerException producerFailure) {
                throw producerFailure;
            }
            if (failure.getCause() instanceof InterruptedException interruption) {
                throw interruption;
            }
            throw new ProducerException(operation + " failed: " + failure.getCause().getClass().getSimpleName(), failure);
        } finally {
            pending.cancel(true);
        }
    }

    public boolean failed() {
        return failed.get();
    }

    /** Interrupts waits and in-flight HTTP futures, including startup; ordinary shutdown is not a failure. */
    public void stop() {
        Thread running;
        synchronized (this) {
            stopped = true;
            running = controller;
            if (running != null) {
                running.interrupt();
                phases.shutdownNow();
            }
        }
        if (running != null && running != Thread.currentThread()) {
            try {
                running.join(Duration.ofSeconds(5));
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public record Settings(String runName, int rejectedOrderPercent, Duration orderDeadline, Duration startupDeadline) {
        public Settings {
            OrderPlanner.validateRunName(runName);
            new CurrencySelector(rejectedOrderPercent);
            positiveBounded(orderDeadline, Duration.ofMinutes(10));
            positiveBounded(startupDeadline, Duration.ofMinutes(5));
        }

        public static void positiveBounded(Duration duration, Duration maximum) {
            if (duration == null || duration.isZero() || duration.isNegative() || duration.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("Invalid producer duration");
            }
        }
    }
}
