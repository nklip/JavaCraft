package dev.nklip.javacraft.shardshop.workload.control;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The live traffic setting of one workload process, with its measurements. The setting lives only in this process:
 * a browser refresh keeps it, and a process restart restores the configured default rate, unpaused. Each update is
 * atomic and increments the revision; an invalid update changes nothing.
 */
public final class WorkloadControl {
    private static final int RATE_SCALE = 3;

    private final Settings settings;
    private final RatePacer pacer;
    private final Throughput throughput;
    private final Traffic traffic;
    private final LongSupplier nanoTime;
    private final AtomicInteger inFlight = new AtomicInteger();
    private BigDecimal target;
    private boolean paused;
    private long revision;
    private Phase phase;
    private volatile Supplier<ControlStatus.Run> run = () -> null;
    private volatile Supplier<ControlStatus.Producer> producer = () -> null;

    public WorkloadControl(Settings settings, Phase initial) {
        this(settings, initial, System::nanoTime, Condition::awaitNanos);
    }

    WorkloadControl(Settings settings, Phase initial, LongSupplier nanoTime, RatePacer.Waiter waiter) {
        this.settings = Objects.requireNonNull(settings);
        this.phase = Objects.requireNonNull(initial);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.target = settings.defaultRate();
        this.pacer = new RatePacer(target.doubleValue(), nanoTime, waiter);
        this.throughput = new Throughput(nanoTime);
        this.traffic = new Traffic(settings.categories());
    }

    public Settings settings() {
        return settings;
    }

    public Traffic traffic() {
        return traffic;
    }

    /** Sets the target rate. Zero stops new work as Pause does; the pause state itself does not change. */
    public synchronized ControlStatus rate(BigDecimal rate) {
        target = settings.validRate(rate);
        return changed();
    }

    /** Stops new work after the operations in flight; the target rate stays for Resume. */
    public synchronized ControlStatus pause() {
        paused = true;
        return changed();
    }

    public synchronized ControlStatus resume() {
        paused = false;
        return changed();
    }

    public synchronized void phase(Phase next) {
        phase = Objects.requireNonNull(next);
    }

    public synchronized Phase phase() {
        return phase;
    }

    /** The status of a finite run; only the seeder registers one. */
    public void run(Supplier<ControlStatus.Run> view) {
        this.run = Objects.requireNonNull(view);
    }

    /** Producer-specific backlog and latency measurements. This supplier must not send backend requests. */
    public void producer(Supplier<ControlStatus.Producer> view) {
        this.producer = Objects.requireNonNull(view);
    }

    /** Waits for the next paced start; while the effective rate is zero, waits until it becomes positive. */
    public void awaitStart() throws InterruptedException {
        pacer.await();
    }

    /** Optional timing of the granted start; the default awaitStart path allocates no timing result. */
    public RatePacer.StartTiming awaitStartTiming() throws InterruptedException {
        return pacer.awaitTiming();
    }

    /**
     * Paces and admits one operation atomically with Pause/rate updates. A Pause after admission sees it in flight;
     * a Pause before admission holds it, even if the pacer's preceding wait has just returned.
     */
    public Operation awaitOperation() throws InterruptedException {
        while (true) {
            pacer.await();
            synchronized (this) {
                if (!paused && target.signum() > 0) {
                    return begin();
                }
            }
        }
    }

    /** Waits only while the effective rate is zero, without taking a paced start. */
    public void awaitOpen() throws InterruptedException {
        pacer.awaitOpen();
    }

    /** Counts a start that did not wait in the paced schedule; the next paced start keeps one interval after it. */
    public void markStart() {
        pacer.mark();
    }

    /** Counts one start for the achieved rate. */
    public void started() {
        throughput.event();
    }

    /** Marks one bounded operation as in flight until the returned handle closes. */
    public Operation begin() {
        inFlight.incrementAndGet();
        return new Operation(nanoTime.getAsLong());
    }

    public ControlStatus status() {
        BigDecimal currentTarget;
        boolean currentPaused;
        long currentRevision;
        Phase currentPhase;
        synchronized (this) {
            currentTarget = target;
            currentPaused = paused;
            currentRevision = revision;
            currentPhase = phase;
        }
        BigDecimal effective = currentPaused ? BigDecimal.ZERO : currentTarget;
        int running = inFlight.get();
        Throughput.Window window = throughput.window();
        String state = switch (currentPhase) {
            case ACTIVE -> effective.signum() == 0 ? running > 0 ? "PAUSING" : "PAUSED" : "RUNNING";
            default -> currentPhase.name();
        };
        ControlStatus.Limit limit = null;
        if (currentPhase == Phase.ACTIVE && effective.signum() > 0 && window.meanOperationSeconds() > 0) {
            double achievable = settings.concurrency() / window.meanOperationSeconds();
            if (achievable < effective.doubleValue()) {
                limit = new ControlStatus.Limit("CONCURRENCY", settings.concurrency(),
                        decimal(window.meanOperationSeconds()), decimal(achievable));
            }
        }
        List<ControlStatus.TrafficCategory> categories = traffic.snapshot();
        long requests = categories.stream().mapToLong(ControlStatus.TrafficCategory::requests).sum();
        long errors = categories.stream().mapToLong(ControlStatus.TrafficCategory::errors).sum();
        return new ControlStatus(settings.workload(), settings.unit(), state, currentTarget, currentPaused, effective,
                settings.defaultRate(), settings.maximumRate(), settings.rateStep(), decimal(window.rate()),
                window.total(), running, requests, errors, categories, limit, run.get(), producer.get(), currentRevision);
    }

    private ControlStatus changed() {
        revision++;
        pacer.rate(paused ? 0 : target.doubleValue());
        return status();
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Text form of a rate without an exponent or trailing zeros, for example 0.5 or 200. */
    static BigDecimal normalized(BigDecimal rate) {
        BigDecimal stripped = rate.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    public enum Phase {
        /** No run started yet; only the interactive seeder. */
        IDLE,
        /** Startup waits for the catalog or creates buyers; no paced work yet. */
        WAITING,
        /** Paced work: the state is RUNNING, PAUSING or PAUSED. */
        ACTIVE,
        /** The last finite run succeeded; only the seeder. */
        COMPLETED,
        FAILED
    }

    /** One bounded operation in flight; closing it again does nothing. */
    public final class Operation implements AutoCloseable {
        private final long started;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Operation(long started) {
            this.started = started;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
                throughput.operation(nanoTime.getAsLong() - started);
            }
        }
    }

    /**
     * The fixed control properties of one workload. {@code concurrency} is the number of operations that can be in
     * flight at the same time; the status uses it to show when operation time limits the achieved rate.
     */
    public record Settings(String workload, String unit, BigDecimal defaultRate, BigDecimal maximumRate,
                           BigDecimal rateStep, int concurrency, List<String> categories) {
        public Settings {
            Objects.requireNonNull(workload);
            Objects.requireNonNull(unit);
            categories = List.copyOf(categories);
            if (maximumRate == null || maximumRate.signum() <= 0 || maximumRate.stripTrailingZeros().scale() > RATE_SCALE
                    || rateStep == null || rateStep.signum() <= 0 || rateStep.compareTo(maximumRate) > 0
                    || rateStep.stripTrailingZeros().scale() > RATE_SCALE || concurrency < 1 || categories.isEmpty()) {
                throw new IllegalArgumentException("Invalid workload control settings");
            }
            maximumRate = normalized(maximumRate);
            rateStep = normalized(rateStep);
            try {
                defaultRate = validRate(defaultRate, maximumRate);
            } catch (ControlFailure invalid) {
                throw new IllegalArgumentException("Invalid default workload rate");
            }
        }

        /** The normalized rate, or {@link ControlFailure} when it is negative, above the maximum or too precise. */
        public BigDecimal validRate(BigDecimal rate) {
            return validRate(rate, maximumRate);
        }

        private static BigDecimal validRate(BigDecimal rate, BigDecimal maximum) {
            if (rate == null || rate.signum() < 0 || rate.compareTo(maximum) > 0
                    || rate.stripTrailingZeros().scale() > RATE_SCALE) {
                throw new ControlFailure(ControlFailure.Reason.INVALID_RATE);
            }
            return normalized(rate);
        }
    }
}
