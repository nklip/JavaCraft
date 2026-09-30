package dev.nklip.javacraft.shardshop.sharding;

/**
 * A CloudNativePG cluster name, also used as the prefix of its {@code -rw} and {@code -ro} Services.
 * The shard inventory's chart schema validates names before they are published.
 */
public record Shard(String clusterName) {

    public Shard {
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalArgumentException("Shard name must not be blank");
        }
    }
}
