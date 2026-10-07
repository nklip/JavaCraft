package dev.nklip.javacraft.shardshop.workload.seeder.config;

import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.net.URI;
import java.time.Duration;

@ConfigMapping(prefix = "shardshop.seeder")
public interface SeederConfiguration {
    /** Base URL of the product Service. It has no default, thus a missing value stops startup. */
    URI productUrl();

    @WithDefault(CatalogDataset.VERSION)
    String catalogVersion();

    @WithDefault("2s")
    Duration connectTimeout();

    /** Complete deadline of one HTTP attempt, including the response body. */
    @WithDefault("5s")
    Duration requestTimeout();

    /** Attempts for each request, 1..10. The seeder repeats only transport failures and 503 responses. */
    @WithDefault("5")
    int maxAttempts();

    @WithDefault("500ms")
    Duration retryBackoff();

    @WithDefault("5s")
    Duration maxRetryBackoff();
}
