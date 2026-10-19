package dev.nklip.javacraft.shardshop.workload.producer.load;

import dev.nklip.javacraft.shardshop.workload.producer.CurrencySelector;
import dev.nklip.javacraft.shardshop.workload.producer.plan.OrderPlanner;
import org.jboss.logging.Logger;

import java.time.Duration;
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
 * One ordered workload stream. Restart reconstructs ordinal zero and recovers every earlier allocation; finished
 * ordinals need no order interval, thus the stream reaches new work after a few HTTP calls per finished order.
 */
public final class OrderProducer {
    private static final Logger LOG = Logger.getLogger(OrderProducer.class);
    private final OrderWorkload workload;
    private final Settings settings;
    private final RetryPolicy.Pause pace;
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean stopped;
    private Thread controller;
    private ExecutorService phases;

    public OrderProducer(OrderWorkload workload, Settings settings) {
        this(workload, settings, Thread::sleep);
    }

    OrderProducer(OrderWorkload workload, Settings settings, RetryPolicy.Pause pace) {
        this.workload = Objects.requireNonNull(workload);
        this.settings = Objects.requireNonNull(settings);
        this.pace = Objects.requireNonNull(pace);
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
            long confirmed = 0;
            long cancelled = 0;
            long recovered = 0;
            boolean caughtUp = false;
            for (long ordinal = 0; !stopped; ordinal++) {
                long current = ordinal;
                var progress = new OrderWorkload.Progress();
                OrderWorkload.Completion outcome;
                try {
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
                // The interval paces orders that were pending at their PUT. A restart recovers finished ordinals
                // without a wait.
                if (!outcome.recovered()) {
                    pace.sleep(settings.orderInterval());
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

    public record Settings(String runName, int rejectedOrderPercent, Duration orderInterval,
                           Duration orderDeadline, Duration startupDeadline) {
        public Settings {
            OrderPlanner.validateRunName(runName);
            new CurrencySelector(rejectedOrderPercent);
            positiveBounded(orderInterval, Duration.ofMinutes(1));
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
