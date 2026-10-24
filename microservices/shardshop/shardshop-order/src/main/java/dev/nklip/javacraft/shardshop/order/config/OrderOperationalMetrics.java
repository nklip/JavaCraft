package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;
import dev.nklip.javacraft.shardshop.order.persistence.JdbcOrderStore;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Publishes cached, bounded database snapshots. Scrapes never perform database IO. */
@ApplicationScoped
@Startup
public class OrderOperationalMetrics {
    private static final String[] SAGA_STATUSES = {"PENDING_STOCK", "PENDING_LEDGER", "CONFIRMED", "CANCELLED"};
    private static final long REFRESH_SECONDS = 10;

    private final ScheduledExecutorService refresh = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("order-metrics").factory());
    private final AtomicBoolean refreshStarted = new AtomicBoolean();
    private final Map<String, Sample> samples = new ConcurrentHashMap<>();
    private final JdbcOrderStore store;
    private final ShardTopology topology;
    private final OrderConfiguration.Process process;

    @Inject
    public OrderOperationalMetrics(MeterRegistry registry, JdbcOrderStore store, ShardTopology topology,
                                   OrderConfiguration config, OrderCdcRelay relay) {
        this(registry, store, topology, config, relay, System::currentTimeMillis);
    }

    OrderOperationalMetrics(MeterRegistry registry, JdbcOrderStore store, ShardTopology topology,
                            OrderConfiguration config, OrderCdcRelay relay, LongSupplier currentTimeMillis) {
        this.store = store;
        this.topology = topology;
        this.process = config.process();
        String process = config.process().name().toLowerCase(java.util.Locale.ROOT);
        for (Shard shard : topology.shards()) {
            String name = shard.clusterName();
            Sample sample = new Sample(currentTimeMillis);
            samples.put(name, sample);
            Tags tags = Tags.of("shard", name, "process", process);
            gauge(registry, "shardshop_order_db_pool_active", "Connections currently borrowed from the pool.",
                    tags, () -> store.poolActive(name));
            gauge(registry, "shardshop_order_db_pool_available", "Idle connections available in the pool.",
                    tags, () -> store.poolAvailable(name));
            gauge(registry, "shardshop_order_db_pool_awaiting", "Threads waiting to borrow a pool connection.",
                    tags, () -> store.poolAwaiting(name));
            gauge(registry, "shardshop_order_db_pool_max", "Configured maximum pool size.",
                    tags, () -> store.poolMaxSize(name));
            gauge(registry, "shardshop_order_db_pool_max_used", "Maximum pool size observed since process start.",
                    tags, () -> store.poolMaxUsed(name));
            if (config.process() == OrderConfiguration.Process.SAGA) {
                gauge(registry, "shardshop_order_metrics_snapshot_success", "Whether the latest shard snapshot succeeded (1) or failed (0).",
                        tags, sample::success);
                gauge(registry, "shardshop_order_metrics_snapshot_age_seconds", "Seconds since the last successful shard snapshot; NaN before the first success.",
                        tags, sample::ageSeconds);
                gauge(registry, "shardshop_order_outbox_pending", "Unpublished order outbox rows.", tags,
                        () -> sample.value(JdbcOrderStore.OperationalSnapshot::pendingOutbox));
                gauge(registry, "shardshop_order_outbox_oldest_pending_seconds", "Age of the oldest unpublished order outbox row.", tags,
                        () -> sample.value(JdbcOrderStore.OperationalSnapshot::oldestPendingOutboxSeconds));
                for (String status : SAGA_STATUSES) {
                    gauge(registry, "shardshop_order_sagas", "Order saga rows by durable status.",
                            tags.and("status", status), () -> sample.sagaStatus(status));
                }
                gauge(registry, "shardshop_order_sagas_reconciliation_exhausted", "Pending ledger sagas at the five-attempt automatic reconciliation limit.",
                        tags, () -> sample.value(JdbcOrderStore.OperationalSnapshot::exhaustedReconciliations));
                gauge(registry, "shardshop_order_quarantine_orphan_rows", "Historical orphan-result quarantine rows, including resolved rows.",
                        tags, () -> sample.value(JdbcOrderStore.OperationalSnapshot::orphanQuarantines));
                gauge(registry, "shardshop_order_quarantine_orphan_unresolved", "Orphan-result quarantine rows without a resolution timestamp.",
                        tags, () -> sample.value(JdbcOrderStore.OperationalSnapshot::unresolvedOrphans));
                gauge(registry, "shardshop_order_quarantine_conflicting_result_rows", "Historical conflicting-result quarantine rows; these are retained incidents.",
                        tags, () -> sample.value(JdbcOrderStore.OperationalSnapshot::conflictingResultQuarantines));
                gauge(registry, "shardshop_order_cdc_slot_exists", "Whether the expected replication slot exists.", tags,
                        () -> sample.booleanValue(JdbcOrderStore.OperationalSnapshot::slotExists));
                gauge(registry, "shardshop_order_cdc_slot_active", "Whether the expected replication slot is active.", tags,
                        () -> sample.booleanValue(JdbcOrderStore.OperationalSnapshot::slotActive));
                gauge(registry, "shardshop_order_cdc_checkpoint_exists", "Whether a valid durable Debezium offset exists for the reader.", tags,
                        () -> sample.booleanValue(JdbcOrderStore.OperationalSnapshot::checkpointExists));
                gauge(registry, "shardshop_order_cdc_retained_wal_bytes", "WAL bytes retained from the slot restart position; NaN when the slot is absent.", tags,
                        () -> sample.value(JdbcOrderStore.OperationalSnapshot::retainedWalBytes));
                gauge(registry, "shardshop_order_cdc_checkpoint_lag_bytes", "Current WAL distance from the durable Debezium checkpoint; byte pressure, not business-event lag.", tags,
                        () -> sample.value(JdbcOrderStore.OperationalSnapshot::checkpointLagBytes));
                gauge(registry, "shardshop_order_cdc_enabled", "Whether this process owns a configured CDC reader for the shard.", tags,
                        () -> relay.readerConfigured(name) ? 1 : 0);
                gauge(registry, "shardshop_order_cdc_recovery_required", "Explicit durable CDC recovery is required (1); ordinary retry is reported separately.", tags,
                        () -> relay.recoveryRequired(name) ? 1 : 0);
                gauge(registry, "shardshop_order_cdc_retrying", "CDC reader is in ordinary reconnect backoff (1).", tags,
                        () -> relay.retrying(name) ? 1 : 0);
            }
        }
    }

    /** Called by the normal saga runtime after --check-startup has exited. */
    public void startSnapshots() {
        if (process == OrderConfiguration.Process.SAGA && refreshStarted.compareAndSet(false, true)) {
            refresh.scheduleWithFixedDelay(this::refreshSnapshots, 0, REFRESH_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void refreshSnapshots() {
        for (Shard shard : topology.shards()) {
            refreshSnapshot(shard.clusterName(), () -> store.operationalSnapshot(shard));
        }
    }

    void refreshSnapshot(String shard, Supplier<JdbcOrderStore.OperationalSnapshot> load) {
        Sample sample = samples.get(shard);
        if (sample == null) throw new IllegalArgumentException("Unknown metrics shard");
        refreshSample(sample, load);
    }

    static void refreshSample(Sample sample, Supplier<JdbcOrderStore.OperationalSnapshot> load) {
        try {
            sample.success(load.get());
        } catch (RuntimeException unavailable) {
            sample.failure();
        }
    }

    private void gauge(MeterRegistry registry, String name, String description, Tags tags,
                       java.util.function.DoubleSupplier value) {
        // Gauge holds its state object weakly; Quarkus owns this registry for the process lifetime.
        Gauge.builder(name, registry, ignored -> value.getAsDouble())
                .tags(tags).description(description).register(registry);
    }

    @PreDestroy
    void stop() {
        refresh.shutdownNow();
    }

    static final class Sample {
        private final AtomicReference<JdbcOrderStore.OperationalSnapshot> current = new AtomicReference<>();
        private final LongSupplier currentTimeMillis;
        private volatile long lastSuccessMillis;
        private volatile boolean succeeded;
        private volatile boolean healthy;

        Sample(LongSupplier currentTimeMillis) {
            this.currentTimeMillis = currentTimeMillis;
        }

        void success(JdbcOrderStore.OperationalSnapshot value) {
            current.set(value);
            lastSuccessMillis = currentTimeMillis.getAsLong();
            succeeded = true;
            healthy = true;
        }

        void failure() { healthy = false; }
        double success() { return healthy ? 1 : 0; }
        double ageSeconds() { return succeeded ? Math.max(0, (currentTimeMillis.getAsLong() - lastSuccessMillis) / 1000.0) : Double.NaN; }
        double value(java.util.function.ToDoubleFunction<JdbcOrderStore.OperationalSnapshot> field) {
            var snapshot = current.get();
            return snapshot == null ? Double.NaN : field.applyAsDouble(snapshot);
        }
        double booleanValue(java.util.function.Function<JdbcOrderStore.OperationalSnapshot, Boolean> field) {
            var snapshot = current.get();
            return snapshot == null ? Double.NaN : field.apply(snapshot) ? 1 : 0;
        }
        double sagaStatus(String status) {
            var snapshot = current.get();
            return snapshot == null ? Double.NaN : snapshot.sagaStatuses().getOrDefault(status, 0L);
        }
    }
}
