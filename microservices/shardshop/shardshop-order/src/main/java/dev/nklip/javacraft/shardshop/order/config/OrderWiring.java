package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.order.ordering.OrderService;
import dev.nklip.javacraft.shardshop.order.ordering.OrderListingService;
import dev.nklip.javacraft.shardshop.order.persistence.JdbcOrderStore;
import dev.nklip.javacraft.shardshop.order.stock.StockCoordinator;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The store and the settings start before the default startup priority: a configuration error stops the start
 * before any bean injects the generator, which reserves a lifetime slot.
 */
@Singleton
public class OrderWiring {
    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    JdbcOrderStore store(OrderConfiguration config, ShardTopology topology) {
        return new JdbcOrderStore(primaryEndpoints(config, topology), config.maxPoolSize());
    }

    static Map<String, Endpoint> primaryEndpoints(OrderConfiguration config, ShardTopology topology) {
        Map<String, Endpoint> primary = new HashMap<>();
        for (var shard : topology.shards()) {
            String name = shard.clusterName();
            var connection = config.connections().get(name);
            String username = connection == null ? "order_app" : connection.username();
            String password = connection == null ? "" : connection.password().orElse("");
            String primaryUrl = "jdbc:postgresql://" + name + "-rw:5432/shardshop";
            String defaultCertificate = "/etc/shardshop/order/" + name + "/ca.crt";
            String certificate = connection == null
                    ? defaultCertificate
                    : connection.rootCertificate().orElse(defaultCertificate);
            primary.put(name, new Endpoint(JdbcUrls.secure(connection == null
                    ? primaryUrl
                    : connection.primaryUrl().orElse(primaryUrl), certificate), username, password)
            );
        }
        if (!primary.keySet().containsAll(config.connections().keySet())) {
            throw new IllegalArgumentException("Order connections must name only configured routing shards");
        }
        return Map.copyOf(primary);
    }

    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    OrderService.Settings settings(OrderConfiguration config) {
        Duration catalogDeadline = config.catalogDeadline();
        // Leave room for the fixed one-second acquisition bound after dispatch, while
        // retaining the HTTP ceiling for two three-second order phases and catalog IO.
        if (catalogDeadline == null || catalogDeadline.compareTo(Duration.ofSeconds(2)) < 0
                || catalogDeadline.compareTo(Duration.ofSeconds(3)) > 0) {
            throw new IllegalArgumentException("Order catalog deadline must be between 2s and 3s");
        }
        return new OrderService.Settings(config.maxIdCandidates(), config.catalogConcurrency(), catalogDeadline);
    }

    @Produces
    @ApplicationScoped
    @Startup
    OrderService service(JdbcOrderStore store, OrderService.Settings settings, IdGenerator generator,
                         ShardRouter router, ShardTopology topology) {
        return new OrderService(store, generator, router, topology, settings);
    }

    @Produces
    @ApplicationScoped
    OrderListingService listingService(JdbcOrderStore store, ShardTopology topology) {
        return new OrderListingService(store, topology);
    }

    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    StockWorker.Settings stockSettings(OrderConfiguration config) {
        var stock = config.stock();
        return new StockWorker.Settings(config.process() == OrderConfiguration.Process.SAGA && stock.enabled(), stock.pollInterval(), stock.lease(), stock.batchSize());
    }

    @Produces
    @Singleton
    StockWorker stockWorker(JdbcOrderStore store, StockWorker.Settings settings, IdGenerator generator,
                            ShardRouter router, ShardTopology topology) {
        return new StockWorker(new StockCoordinator(store, router, generator, settings.lease()), topology, settings);
    }

    void closeStockWorker(@Disposes StockWorker worker) {
        worker.close();
    }

    void close(@Disposes JdbcOrderStore store) {
        store.close();
    }
}
