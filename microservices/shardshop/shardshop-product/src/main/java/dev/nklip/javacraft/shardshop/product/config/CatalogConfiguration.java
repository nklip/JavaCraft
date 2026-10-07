package dev.nklip.javacraft.shardshop.product.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "shardshop.catalog")
public interface CatalogConfiguration {
    enum ReadProfile { PRIMARY, REPLICA }

    @WithDefault("primary")
    ReadProfile readProfile();

    @WithDefault("4")
    int maxPoolSize();

    @WithDefault("1024")
    int maxIdCandidates();

    Map<String, Connection> connections();

    interface Connection {
        Optional<String> primaryUrl();

        Optional<String> replicaUrl();

        Optional<String> rootCertificate();

        @WithDefault("product_app")
        String username();

        Optional<String> password();
    }
}
