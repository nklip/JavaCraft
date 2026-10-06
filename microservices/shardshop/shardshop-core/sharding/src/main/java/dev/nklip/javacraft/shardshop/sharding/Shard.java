package dev.nklip.javacraft.shardshop.sharding;

/**
 * A CloudNativePG cluster and its immutable business region. The cluster name also
 * prefixes its {@code -rw} and {@code -ro} Services; the chart validates names before publication.
 */
public record Shard(String clusterName, String region) {

    public Shard {
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalArgumentException("Shard name must not be blank");
        }
        if (region == null || !region.matches("US|EU|ASIA")) {
            throw new IllegalArgumentException("Shard region must be US, EU or ASIA");
        }
    }
}
