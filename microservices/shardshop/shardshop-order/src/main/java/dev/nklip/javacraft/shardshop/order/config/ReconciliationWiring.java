package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.order.persistence.JdbcOrderStore;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationCoordinator;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Singleton;

import java.time.Clock;

/** Validates reconciliation settings before a JVM generator slot is reserved. */
@Singleton
public class ReconciliationWiring {
    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    ReconciliationWorker.Settings settings(OrderConfiguration order) {
        var reconciliation = order.reconciliation();
        return new ReconciliationWorker.Settings(order.process() == OrderConfiguration.Process.SAGA
                && reconciliation.enabled(), reconciliation.pollInterval(), reconciliation.batchSize());
    }

    @Produces
    @Singleton
    ReconciliationWorker worker(JdbcOrderStore store, IdGenerator generator, ShardTopology topology,
                                ReconciliationWorker.Settings settings) {
        Clock clock = Clock.systemUTC();
        return new ReconciliationWorker(new ReconciliationCoordinator(store, generator, clock), topology, settings, clock);
    }

    void close(@Disposes ReconciliationWorker worker) {
        worker.close();
    }
}
