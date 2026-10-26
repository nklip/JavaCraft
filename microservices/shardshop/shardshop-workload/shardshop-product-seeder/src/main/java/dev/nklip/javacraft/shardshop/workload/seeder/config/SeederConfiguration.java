package dev.nklip.javacraft.shardshop.workload.seeder.config;

import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.math.BigDecimal;
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

    /** Attempts for each request, 1..10. Repeats transport failures, 503 and verification-only 404 responses. */
    @WithDefault("5")
    int maxAttempts();

    @WithDefault("500ms")
    Duration retryBackoff();

    @WithDefault("5s")
    Duration maxRetryBackoff();

    /**
     * Initial outgoing HTTP requests per second, including creation, verification and repeated attempts; 0..1000 with
     * at most three decimals. The bootstrap Job requires a positive rate. The control page of the interactive mode
     * changes the live rate; a restart returns to this value.
     */
    @WithDefault("100")
    BigDecimal requestRate();

    /**
     * False: the finite bootstrap Job seeds once and exits, without HTTP serving. True: the process serves the
     * control page and starts one finite seed run at a time on operator request, until it stops.
     */
    @WithDefault("false")
    boolean interactive();
}
