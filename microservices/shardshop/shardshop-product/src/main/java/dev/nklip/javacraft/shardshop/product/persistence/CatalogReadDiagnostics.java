package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabaseTimings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.FunctionCounter;
import io.agroal.api.AgroalDataSource;
import java.util.Map;
import io.micrometer.core.instrument.Timer;

import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Fixed-cardinality read timers: no IDs, SQL, shard names, histograms or retained request samples. */
public final class CatalogReadDiagnostics {
    private final MeterRegistry registry;
    private final DatabaseTimings primary;
    private final DatabaseTimings replica;

    public CatalogReadDiagnostics(MeterRegistry registry) {
        this(registry, System::nanoTime);
    }

    CatalogReadDiagnostics(MeterRegistry registry, LongSupplier nanoTime) {
        this.registry = registry;
        primary = timings(registry, "primary", nanoTime);
        replica = timings(registry, "replica", nanoTime);
    }

    DatabaseTimings timings(boolean replica) {
        return replica ? this.replica : primary;
    }

    void registerPools(Map<JdbcCatalogStore.PoolKey, AgroalDataSource> pools) {
        for (boolean replica : new boolean[]{false, true}) {
            var selected = pools.entrySet().stream().filter(entry -> entry.getKey().replica() == replica)
                    .map(Map.Entry::getValue).toList();
            String target = replica ? "replica" : "primary";
            Gauge.builder("shardshop.product.read.pool.connections", selected,
                            values -> values.stream().mapToLong(pool -> pool.getMetrics().activeCount()).sum())
                    .tags("target", target, "state", "active").strongReference(true).register(registry);
            Gauge.builder("shardshop.product.read.pool.connections", selected,
                            values -> values.stream().mapToLong(pool -> pool.getMetrics().availableCount()).sum())
                    .tags("target", target, "state", "available").strongReference(true).register(registry);
            Gauge.builder("shardshop.product.read.pool.connections", selected,
                            values -> values.stream().mapToLong(pool -> pool.getMetrics().awaitingCount()).sum())
                    .tags("target", target, "state", "awaiting").strongReference(true).register(registry);
            FunctionCounter.builder("shardshop.product.read.pool.creations", selected,
                            values -> values.stream().mapToLong(pool -> pool.getMetrics().creationCount()).sum())
                    .tags("target", target).register(registry);
        }
    }

    private static DatabaseTimings timings(MeterRegistry registry, String target, LongSupplier nanoTime) {
        EnumMap<DatabaseTimings.Phase, Timer[]> timers = new EnumMap<>(DatabaseTimings.Phase.class);
        for (DatabaseTimings.Phase phase : DatabaseTimings.Phase.values()) {
            Timer[] outcomes = new Timer[2];
            for (int outcome = 0; outcome < outcomes.length; outcome++) {
                outcomes[outcome] = Timer.builder("shardshop.product.read.database.phase")
                        .description("Product read database phases; acquisition includes pool wait, creation and validation")
                        .tags("target", target, "phase", phase.name().toLowerCase(Locale.ROOT),
                                "outcome", outcome == 0 ? "success" : "failure")
                        .register(registry);
            }
            timers.put(phase, outcomes);
        }
        return new DatabaseTimings(nanoTime, (phase, nanoseconds, success) ->
                timers.get(phase)[success ? 0 : 1].record(nanoseconds, TimeUnit.NANOSECONDS));
    }
}
