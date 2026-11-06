package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.common.amqp.QueueConsumer;
import dev.nklip.javacraft.shardshop.order.messaging.OrderResultConsumer;
import dev.nklip.javacraft.shardshop.order.persistence.JdbcOrderStore;
import dev.nklip.javacraft.shardshop.order.result.ResultService;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Singleton;

/** Validates worker configuration before generator reservation; construction performs no broker IO. */
@Singleton
public class ResultWiring {
    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    OrderResultConsumer.Settings resultSettings(OrderConfiguration order, CdcConfiguration cdc) {
        var broker = cdc.broker();
        boolean enabled = order.process() == OrderConfiguration.Process.SAGA && order.results().enabled();
        // Enabled CDC validates this same credential in CdcWiring, before any generator is reserved.
        if (enabled && !cdc.enabled() && broker.password().orElse("").isBlank()) {
            throw new IllegalArgumentException("Result consumption is enabled but "
                    + "shardshop.order.cdc.broker.password is not set");
        }
        int concurrency = order.results().concurrency();
        // The saga pool adds one connection for each handler (OrderWiring.poolSize).
        if (concurrency < 1 || concurrency > QueueConsumer.MAX_CONCURRENCY) {
            throw new IllegalArgumentException("shardshop.order.results.concurrency must be between 1 and "
                    + QueueConsumer.MAX_CONCURRENCY);
        }
        return new OrderResultConsumer.Settings(enabled, CdcWiring.brokerSettings(cdc), concurrency);
    }

    @Produces
    @Singleton
    OrderResultConsumer resultConsumer(JdbcOrderStore store, ShardRouter router, OrderResultConsumer.Settings settings) {
        ResultService service = new ResultService(store, router);
        return new OrderResultConsumer(settings, service::handle);
    }

    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    ProfitWorker.Settings profitSettings(OrderConfiguration order) {
        var profit = order.profit();
        return new ProfitWorker.Settings(order.process() == OrderConfiguration.Process.SAGA && profit.enabled(),
                profit.pollInterval(), profit.batchSize(), profit.shardConcurrency(), profit.itemConcurrency());
    }

    @Produces
    @Singleton
    ProfitWorker profitWorker(JdbcOrderStore store, ShardRouter router, ShardTopology topology,
                              ProfitWorker.Settings settings) {
        return new ProfitWorker(store, router, topology, settings);
    }

    void closeConsumer(@Disposes OrderResultConsumer consumer) {
        consumer.close();
    }

    void closeProfit(@Disposes ProfitWorker worker) {
        worker.close();
    }
}
