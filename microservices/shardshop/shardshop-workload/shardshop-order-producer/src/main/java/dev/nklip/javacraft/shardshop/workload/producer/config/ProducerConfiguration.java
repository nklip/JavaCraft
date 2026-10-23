package dev.nklip.javacraft.shardshop.workload.producer.config;

import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;

@ConfigMapping(prefix = "shardshop.producer")
public interface ProducerConfiguration {
    URI productUrl();
    URI orderUrl();
    /** Required deployment-owned retry identity. Freeze dataset versions and selection settings for this run. */
    String runName();

    @WithDefault(CatalogDataset.VERSION)
    String catalogVersion();
    @WithDefault(BuyerDataset.VERSION)
    String buyerVersion();
    /** -1 selects all supported currencies; 0..100 explicitly scopes the USD/EUR rejection drill. */
    @WithDefault("-1")
    int rejectedOrderPercent();
    @WithDefault("2s")
    Duration connectTimeout();
    @WithDefault("5s")
    Duration requestTimeout();
    /**
     * Initial new order starts per second, 0..30 with at most three decimals; zero starts paused. The control page changes the live rate; a restart returns to this value.
     */
    @WithDefault("0.5")
    BigDecimal orderRate();
    /** Concurrent allocation + PUT operations; 1..12. */
    @WithDefault("4")
    int submissionConcurrency();
    /** Concurrent status requests; 1..12, independent of writes. */
    @WithDefault("2")
    int observerConcurrency();
    /** Reserves capacity for writes as well as accepted non-terminal orders; concurrency..10000. */
    @WithDefault("100")
    int maxNonTerminalOrders();
    @WithDefault("250ms")
    Duration pollInterval();
    /** Complete allocation, submission and observation budget for one ordinal, excluding pacing/backpressure waits. */
    @WithDefault("60s")
    Duration orderDeadline();
    /** Separate complete budgets for catalog resolution and creation/recovery of all buyers. */
    @WithDefault("5m")
    Duration startupDeadline();
    @WithDefault("5")
    int maxAttempts();
    @WithDefault("100ms")
    Duration retryBackoff();
    @WithDefault("2s")
    Duration maxRetryBackoff();
}
