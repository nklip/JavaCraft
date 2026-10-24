package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.cdc.TransportCleanupWorker;
import dev.nklip.javacraft.shardshop.order.persistence.JdbcOrderStore;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Singleton;

import java.util.ArrayList;

/** Validates cleanup settings before a JVM generator slot is reserved. */
@Singleton
public class CleanupWiring {
    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    TransportCleanupWorker.Settings settings(OrderConfiguration order) {
        var cleanup = order.cleanup();
        return new TransportCleanupWorker.Settings(order.process() == OrderConfiguration.Process.SAGA
                && cleanup.enabled(), cleanup.retention(), cleanup.pollInterval(), cleanup.batchSize());
    }

    @Produces
    @Singleton
    TransportCleanupWorker worker(OrderConfiguration order, ShardTopology topology, JdbcOrderStore store,
                                  TransportCleanupWorker.Settings settings) {
        var endpoints = OrderWiring.primaryEndpoints(order, topology);
        var shards = new ArrayList<TransportCleanupWorker.ShardSettings>();
        for (var shard : topology.shards()) {
            shards.add(new TransportCleanupWorker.ShardSettings(shard,
                    endpoints.get(shard.clusterName())));
        }
        return new TransportCleanupWorker(shards, store, settings);
    }

    void close(@Disposes TransportCleanupWorker worker) {
        worker.close();
    }
}
