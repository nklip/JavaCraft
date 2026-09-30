package dev.nklip.javacraft.shardshop.sharding;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, ordered deployment snapshot. Membership and order determine row ownership;
 * replacing this snapshot requires a restart and, for existing data, a migration.
 */
public record ShardTopology(List<Shard> shards) {

    public ShardTopology {
        shards = List.copyOf(shards);
        if (shards.isEmpty() || new HashSet<>(shards).size() != shards.size()) {
            throw new IllegalArgumentException("Shard topology must contain a nonempty list of distinct shards");
        }
    }

    public static ShardTopology fromNames(String clusterNames) {
        Objects.requireNonNull(clusterNames, "Shard cluster names are required");
        return new ShardTopology(Arrays.stream(clusterNames.split(",", -1)).map(Shard::new).toList());
    }
}
