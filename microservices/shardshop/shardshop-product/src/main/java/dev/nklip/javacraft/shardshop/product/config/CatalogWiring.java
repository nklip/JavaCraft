package dev.nklip.javacraft.shardshop.product.config;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogService;
import dev.nklip.javacraft.shardshop.product.persistence.JdbcCatalogStore;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.Map;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Singleton
public class CatalogWiring {
    @Produces
    @Singleton
    @Startup
    JdbcCatalogStore store(CatalogConfiguration config, ShardTopology topology) {
        Map<String, JdbcCatalogStore.Endpoint> primary = new HashMap<>();
        Map<String, JdbcCatalogStore.Endpoint> replica = new HashMap<>();
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
            primary.put(name, new JdbcCatalogStore.Endpoint(secureUrl(connection == null
                    ? primaryUrl
                    : connection.primaryUrl().orElse(primaryUrl), certificate), username, password)
            );
            replica.put(name, new JdbcCatalogStore.Endpoint(secureUrl(connection == null
                    ? replicaUrl
                    : connection.replicaUrl().orElse(replicaUrl), certificate), username, password)
            );
        }
        if (!primary.keySet().containsAll(config.connections().keySet())) {
            throw new IllegalArgumentException("Catalog connections must name only configured routing shards");
        }
        return new JdbcCatalogStore(primary, replica, config.maxPoolSize());
    }

    private String secureUrl(String url, String certificate) {
        String mode = parameter(url, "sslmode");
        if (mode == null || mode.isBlank()) {
            mode = "verify-full";
            url = append(url, "sslmode", mode);
        }
        if (mode.equalsIgnoreCase("verify-full") || mode.equalsIgnoreCase("verify-ca")) {
            String root = parameter(url, "sslrootcert");
            if (root == null || root.isBlank()) {
                if (certificate.isBlank()) {
                    throw new IllegalArgumentException("Catalog root certificate must not be blank");
                }
                url = append(url, "sslrootcert", certificate);
            }
        }
        return url;
    }

    private String parameter(String url, String name) {
        int query = url.indexOf('?');
        if (query < 0) {
            return null;
        }
        String value = null;
        for (String part : url.substring(query + 1).split("&")) {
            if (part.startsWith(name + "=")) {
                try {
                    value = URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("Invalid catalog TLS parameter encoding");
                }
            }
        }
        return value;
    }

    private String append(String url, String name, String value) {
        return url + (url.contains("?") ? "&" : "?") + name + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Produces
    @Singleton
    @Startup
    CatalogService service(JdbcCatalogStore store, IdGenerator generator, ShardRouter router,
                           ShardTopology topology, CatalogConfiguration config) {
        return new CatalogService(store, generator, router, topology,
                config.readProfile() == CatalogConfiguration.ReadProfile.REPLICA, config.maxIdCandidates());
    }

    void close(@Disposes JdbcCatalogStore store) {
        store.close();
    }
}
