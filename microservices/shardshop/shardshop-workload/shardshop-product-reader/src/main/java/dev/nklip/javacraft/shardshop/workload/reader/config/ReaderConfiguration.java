package dev.nklip.javacraft.shardshop.workload.reader.config;

import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.net.URI;
import java.time.Duration;

/** The defaults are the HTTP connection policy of the architecture for the load profile. */
@ConfigMapping(prefix = "shardshop.reader")
public interface ReaderConfiguration {
    /** Base URL of the product Service. It has no default, thus a missing value stops startup. */
    URI productUrl();

    @WithDefault(CatalogDataset.VERSION)
    String catalogVersion();

    /** Total request starts per second for all workers, 1..10000. */
    @WithDefault("200")
    int requestRate();

    /** Concurrent workers, from the number of connections to 256. */
    @WithDefault("16")
    int workers();

    /** HTTP/1.1 connections to the product Service, 1..64. */
    @WithDefault("16")
    int connections();

    @WithDefault("2s")
    Duration connectTimeout();

    /**
     * Complete deadline of one read, including the wait for a connection and all attempts. At least 5s, thus
     * product's 503 within its 4-second server deadline arrives before it.
     */
    @WithDefault("5s")
    Duration requestDeadline();

    /** Attempts for a read without an HTTP response, 1..5, all within the request deadline. */
    @WithDefault("3")
    int maxAttempts();

    /** Fixed delay before a new attempt. */
    @WithDefault("100ms")
    Duration retryInterval();

    /** A connection retires at its next use after this time. */
    @WithDefault("30s")
    Duration maxConnectionLifetime();

    @WithDefault("10s")
    Duration idleTimeout();

    /** Time between two metric reports, at least 1s. */
    @WithDefault("30s")
    Duration reportInterval();
}
