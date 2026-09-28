package dev.nklip.javacraft.shardshop.sharding;

/**
 * The three database shards in routing-index order: 0 is shard-a, 1 is shard-b and 2 is shard-c.
 */
public enum Shard {
    SHARD_A("shard-a"),
    SHARD_B("shard-b"),
    SHARD_C("shard-c");

    private final String clusterName;

    Shard(String clusterName) {
        this.clusterName = clusterName;
    }

    /**
     * @return the CloudNativePG cluster name, which also prefixes its {@code -rw} and {@code -ro} Services
     */
    public String clusterName() {
        return clusterName;
    }
}
