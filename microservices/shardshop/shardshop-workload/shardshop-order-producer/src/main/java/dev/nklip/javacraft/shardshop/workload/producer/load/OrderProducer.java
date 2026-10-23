package dev.nklip.javacraft.shardshop.workload.producer.load;

import dev.nklip.javacraft.shardshop.workload.control.ControlStatus;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.producer.CurrencySelector;
import dev.nklip.javacraft.shardshop.workload.producer.http.OrderStatus;
import dev.nklip.javacraft.shardshop.workload.producer.plan.OrderPlanner;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/** Dense deterministic submission stream, with a separate bounded observer and a bounded outstanding window. */
public final class OrderProducer {
    public static final BigDecimal MAX_ORDER_RATE = new BigDecimal("30");
    public static final int MAX_CONCURRENCY = 12;
    private static final Logger LOG = Logger.getLogger(OrderProducer.class);
    private final OrderWorkload workload;
    private final Settings settings;
    private final WorkloadControl control;
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Semaphore writeSlots;
    private final Semaphore outstandingSlots;
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger outstanding = new AtomicInteger();
    private final AtomicLong recovered = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final Latencies writeLatency = new Latencies();
    private final Latencies sagaLatency = new Latencies();
    private volatile boolean stopped;
    private Thread controller;
    private ExecutorService phases;
    private ExecutorService submissions;
    private ScheduledThreadPoolExecutor observers;
    private ScheduledThreadPoolExecutor deadlines;

    public OrderProducer(OrderWorkload workload, Settings settings, WorkloadControl control) {
        this.workload = Objects.requireNonNull(workload);
        this.settings = Objects.requireNonNull(settings);
        this.control = Objects.requireNonNull(control);
        writeSlots = new Semaphore(settings.submissionConcurrency());
        outstandingSlots = new Semaphore(settings.maxNonTerminalOrders());
        control.producer(this::status);
    }

    public static WorkloadControl.Settings controlSettings(BigDecimal orderRate) {
        return controlSettings(orderRate, 4);
    }

    public static WorkloadControl.Settings controlSettings(BigDecimal orderRate, int concurrency) {
        concurrency(concurrency);
        return new WorkloadControl.Settings("order-producer", "new order starts/s", orderRate, MAX_ORDER_RATE,
                new BigDecimal("0.1"), concurrency, List.of(OrderWorkload.STARTUP, OrderWorkload.ALLOCATION,
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
        // Each outer worker has at most one phase future, bounded by the two independent worker pools.
        phases = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("order-producer-phase-", 0).factory());
        submissions = Executors.newFixedThreadPool(settings.submissionConcurrency(),
                Thread.ofVirtual().name("order-producer-write-", 0).factory());
        observers = new ScheduledThreadPoolExecutor(settings.observerConcurrency(),
                Thread.ofVirtual().name("order-producer-observer-", 0).factory());
        observers.setRemoveOnCancelPolicy(true);
        // Deadline checks must run even when every observer is inside a slow HTTP request.
        deadlines = new ScheduledThreadPoolExecutor(1, Thread.ofVirtual().name("order-producer-deadlines").factory());
        deadlines.setRemoveOnCancelPolicy(true);
        controller = Thread.ofVirtual().name("order-producer").unstarted(() -> run(onFailure));
        controller.start();
    }

    private void run(Runnable onFailure) {
        try {
            var products = timed("Catalog startup", settings.startupDeadline().toNanos(), workload::resolveCatalog);
            var buyers = timed("Buyer creation", settings.startupDeadline().toNanos(), workload::createBuyers);
            OrderPlanner planner = workload.planner(settings.runName(), settings.rejectedOrderPercent(), buyers, products);
            LOG.infof("Order producer ready: sellers=%d products=%d buyers=%d submissionConcurrency=%d observerConcurrency=%d",
                    products.stream().map(product -> product.sellerId()).distinct().count(), products.size(), buyers.size(),
                    settings.submissionConcurrency(), settings.observerConcurrency());
            control.phase(WorkloadControl.Phase.ACTIVE);
            for (long ordinal = 0; !stopped && !failed.get(); ordinal++) {
                // Reserve before pacing: a full window cannot accumulate paced starts for a later catch-up burst.
                outstandingSlots.acquire();
                writeSlots.acquire();
                WorkloadControl.Operation operation = control.awaitOperation();
                if (stopped || failed.get()) {
                    operation.close();
                    break;
                }
                long current = ordinal;
                outstanding.incrementAndGet();
                submissions.execute(() -> write(planner, current, operation, onFailure));
                if (current == Long.MAX_VALUE) {
                    throw new ProducerException("Order request ordinals exhausted", null);
                }
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            if (!stopped && !failed.get()) {
                fail("Order producer interrupted", onFailure);
            }
        } catch (ProducerException failure) {
            fail(failure.getMessage(), onFailure);
        } catch (RuntimeException failure) {
            fail("Unexpected " + failure.getClass().getSimpleName(), onFailure);
        } finally {
            submissions.shutdownNow();
            observers.shutdownNow();
            deadlines.shutdownNow();
            phases.shutdownNow();
        }
    }

    private void write(OrderPlanner planner, long ordinal, WorkloadControl.Operation operation, Runnable onFailure) {
        var progress = new OrderWorkload.Progress(control::started);
        long started = System.nanoTime();
        boolean retained = false;
        try (operation) {
            OrderWorkload.Completion outcome = timed("Order write", settings.orderDeadline().toNanos(),
                    () -> workload.write(planner, settings.runName(), ordinal, progress));
            if (outcome.recovered()) {
                recovered.incrementAndGet();
            } else {
                writeLatency.add(System.nanoTime() - started);
            }
            if (outcome.status().terminal()) {
                terminal(outcome.status(), outcome.recovered(), started);
            } else {
                var observation = new Pending(ordinal, outcome.status(), outcome.recovered(), started, progress);
                pending.put(ordinal, observation);
                retained = true;
                observation.deadline = deadlines.schedule(() -> fail("Order completion deadline exceeded ("
                                + progress.describe(ordinal) + ")", onFailure),
                        Math.max(0, remaining(started)), TimeUnit.NANOSECONDS);
                schedule(observation, onFailure);
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            fail("Order write interrupted (" + progress.describe(ordinal) + ")", onFailure);
        } catch (ProducerException failure) {
            fail(failure.getMessage() + " (" + progress.describe(ordinal) + ")", onFailure);
        } catch (RuntimeException failure) {
            fail("Unexpected " + failure.getClass().getSimpleName() + " (" + progress.describe(ordinal) + ")", onFailure);
        } finally {
            if (!retained) {
                outstanding.decrementAndGet();
                outstandingSlots.release();
            }
            writeSlots.release();
        }
    }

    private void schedule(Pending observation, Runnable onFailure) {
        observers.schedule(() -> observe(observation, onFailure), workload.pollInterval().toNanos(), TimeUnit.NANOSECONDS);
    }

    private void observe(Pending observation, Runnable onFailure) {
        try {
            OrderStatus status = timed("Order completion", remaining(observation.started),
                    () -> workload.observe(observation.status, observation.progress));
            observation.status = status;
            if (status.terminal()) {
                observation.deadline.cancel(false);
                terminal(status, observation.recovered, observation.started);
                pending.remove(observation.ordinal);
                outstanding.decrementAndGet();
                outstandingSlots.release();
            } else if (!stopped && !failed.get()) {
                schedule(observation, onFailure);
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            fail("Order observation interrupted (" + observation.progress.describe(observation.ordinal) + ")", onFailure);
        } catch (ProducerException failure) {
            fail(failure.getMessage() + " (" + observation.progress.describe(observation.ordinal) + ")", onFailure);
        } catch (RuntimeException failure) {
            fail("Unexpected " + failure.getClass().getSimpleName() + " ("
                    + observation.progress.describe(observation.ordinal) + ")", onFailure);
        }
    }

    private long remaining(long started) {
        return settings.orderDeadline().toNanos() - (System.nanoTime() - started);
    }

    private void terminal(OrderStatus status, boolean wasRecovered, long started) {
        if (!wasRecovered) {
            if (status.status().equals("CONFIRMED")) {
                confirmed.incrementAndGet();
            } else {
                cancelled.incrementAndGet();
            }
            sagaLatency.add(System.nanoTime() - started);
        }
        long total = confirmed.get() + cancelled.get() + recovered.get();
        if (total % 100 == 1) {
            LOG.infof("Order producer progress: confirmed=%d cancelled=%d recovered=%d backlog=%d",
                    confirmed.get(), cancelled.get(), recovered.get(), pending.size());
        }
    }

    private ControlStatus.Producer status() {
        return new ControlStatus.Producer(settings.submissionConcurrency(), settings.observerConcurrency(),
                settings.maxNonTerminalOrders(), pending.size(), outstanding.get() >= settings.maxNonTerminalOrders(),
                recovered.get(), confirmed.get(), cancelled.get(), writeLatency.snapshot(), sagaLatency.snapshot());
    }

    private void fail(String reason, Runnable onFailure) {
        if (!stopped && failed.compareAndSet(false, true)) {
            control.phase(WorkloadControl.Phase.FAILED);
            LOG.error("Order producer failed: " + reason);
            controller.interrupt();
            onFailure.run();
        }
    }

    private <T> T timed(String operation, long nanos, Callable<T> work) throws ProducerException, InterruptedException {
        if (nanos <= 0) {
            throw new ProducerException(operation + " deadline exceeded", null);
        }
        Future<T> future = phases.submit(work);
        try {
            return future.get(nanos, TimeUnit.NANOSECONDS);
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
            future.cancel(true);
        }
    }

    public boolean failed() {
        return failed.get();
    }

    /** Interrupts pacing, startup, HTTP work and observations; ordinary shutdown is not a failure. */
    public void stop() {
        Thread running;
        synchronized (this) {
            stopped = true;
            running = controller;
            if (running != null) {
                running.interrupt();
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

    private static void concurrency(int concurrency) {
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("Producer concurrency must be 1.." + MAX_CONCURRENCY);
        }
    }

    public record Settings(String runName, int rejectedOrderPercent, Duration orderDeadline, Duration startupDeadline,
                           int submissionConcurrency, int observerConcurrency, int maxNonTerminalOrders) {
        public Settings(String runName, int rejectedOrderPercent, Duration orderDeadline, Duration startupDeadline) {
            this(runName, rejectedOrderPercent, orderDeadline, startupDeadline, 4, 2, 100);
        }

        public Settings {
            OrderPlanner.validateRunName(runName);
            new CurrencySelector(rejectedOrderPercent);
            positiveBounded(orderDeadline, Duration.ofMinutes(10));
            positiveBounded(startupDeadline, Duration.ofMinutes(5));
            concurrency(submissionConcurrency);
            concurrency(observerConcurrency);
            if (maxNonTerminalOrders < submissionConcurrency || maxNonTerminalOrders > 10_000) {
                throw new IllegalArgumentException("Producer maximum non-terminal orders must be concurrency..10000");
            }
        }

        public static void positiveBounded(Duration duration, Duration maximum) {
            if (duration == null || duration.isZero() || duration.isNegative() || duration.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("Invalid producer duration");
            }
        }
    }

    private static final class Pending {
        private final long ordinal;
        private final boolean recovered;
        private final long started;
        private final OrderWorkload.Progress progress;
        private OrderStatus status;
        private ScheduledFuture<?> deadline;

        private Pending(long ordinal, OrderStatus status, boolean recovered, long started, OrderWorkload.Progress progress) {
            this.ordinal = ordinal;
            this.status = status;
            this.recovered = recovered;
            this.started = started;
            this.progress = progress;
        }
    }
}
