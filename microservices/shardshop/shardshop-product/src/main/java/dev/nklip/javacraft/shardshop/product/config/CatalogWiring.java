package dev.nklip.javacraft.shardshop.product.config;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogService;
import dev.nklip.javacraft.shardshop.product.persistence.JdbcCatalogStore;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.Map;

/**
 * The store and the settings start before the default startup priority: a configuration error stops the start
 * before any bean injects the generator, which reserves a lifetime slot.
 */
@Singleton
public class CatalogWiring {
    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    JdbcCatalogStore store(CatalogConfiguration config, ShardTopology topology) {
        Map<String, Endpoint> primary = new HashMap<>();
        Map<String, Endpoint> replica = new HashMap<>();
        for (var shard : topology.shards()) {
            String name = shard.clusterName();
            var connection = config.connections().get(name);
            String username = connection == null ? "product_app" : connection.username();
            String password = connection == null ? "" : connection.password().orElse("");
            String primaryUrl = "jdbc:postgresql://" + name + "-rw:5432/shardshop";
            String replicaUrl = "jdbc:postgresql://" + name + "-ro:5432/shardshop";
            String defaultCertificate = "/etc/shardshop/catalog/" + name + "/ca.crt";
            String certificate = connection == null
                    ? defaultCertificate
                    : connection.rootCertificate().orElse(defaultCertificate);
            primary.put(name, new Endpoint(JdbcUrls.secure(connection == null
                    ? primaryUrl
                    : connection.primaryUrl().orElse(primaryUrl), certificate), username, password)
            );
            replica.put(name, new Endpoint(JdbcUrls.secure(connection == null
                    ? replicaUrl
                    : connection.replicaUrl().orElse(replicaUrl), certificate), username, password)
            );
        }
        if (!primary.keySet().containsAll(config.connections().keySet())) {
            throw new IllegalArgumentException("Catalog connections must name only configured routing shards");
        }
        return new JdbcCatalogStore(primary, replica, config.maxPoolSize());
    }

    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY - 1)
    CatalogService.Settings settings(CatalogConfiguration config) {
        return new CatalogService.Settings(config.readProfile() == CatalogConfiguration.ReadProfile.REPLICA,
                config.maxIdCandidates());
    }

    @Produces
    @Singleton
    @Startup
    CatalogService service(JdbcCatalogStore store, CatalogService.Settings settings, IdGenerator generator,
                           ShardRouter router, ShardTopology topology) {
        return new CatalogService(store, generator, router, topology, settings);
    }

    void close(@Disposes JdbcCatalogStore store) {
        store.close();
    }
}
