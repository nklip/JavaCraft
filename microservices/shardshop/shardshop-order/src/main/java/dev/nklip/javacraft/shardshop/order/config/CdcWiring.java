package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.common.amqp.BrokerSettings;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Singleton;

import java.util.ArrayList;

@Singleton
public class CdcWiring {
    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    OrderCdcRelay relay(CdcConfiguration config, OrderConfiguration order, ShardTopology topology) {
        var primary = OrderWiring.primaryEndpoints(order, topology);
        if (!primary.keySet().containsAll(config.connections().keySet())) {
            throw new IllegalArgumentException("CDC connections must name only configured routing shards");
        }
        var shards = new ArrayList<OrderCdcRelay.ShardSettings>();
        var broker = config.broker();
        // This producer runs before the generator reservation, so a pod without its CDC Secret burns no ID.
        if (config.enabled()) {
            for (var shard : topology.shards()) {
                String name = shard.clusterName();
                Endpoint application = primary.get(name);
                var credentials = config.connections().get(name);
                String password = credentials == null ? "" : credentials.password().orElse("");
                if (password.isBlank()) {
                    throw new IllegalArgumentException("CDC is enabled but shardshop.order.cdc.connections."
                            + name + ".password is not set");
                }
                Endpoint replication = new Endpoint(application.jdbcUrl(),
                        credentials.username(), password);
                shards.add(new OrderCdcRelay.ShardSettings(name, application, replication));
            }
            if (broker.password().orElse("").isBlank()) {
                throw new IllegalArgumentException("CDC is enabled but shardshop.order.cdc.broker.password is not set");
            }
        }
        return new OrderCdcRelay(shards, brokerSettings(config));
    }

    /** The CDC publisher and the result consumer share the order broker connection and credential. */
    static BrokerSettings brokerSettings(CdcConfiguration config) {
        var broker = config.broker();
        return new BrokerSettings(broker.host(), broker.port(), broker.virtualHost(), broker.username(),
                broker.password().orElse(""), broker.timeout());
    }

    void close(@Disposes OrderCdcRelay relay) {
        relay.close();
    }
}
