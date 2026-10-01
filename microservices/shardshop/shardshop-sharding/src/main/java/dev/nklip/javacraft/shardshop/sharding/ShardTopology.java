package dev.nklip.javacraft.shardshop.sharding;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * An immutable, ordered deployment snapshot. Membership, order and regions determine row ownership;
 * replacing this snapshot requires a restart and, for existing data, a migration.
 */
public record ShardTopology(List<Shard> shards) {

    public ShardTopology {
        shards = List.copyOf(shards);
        if (shards.isEmpty() || shards.stream().map(Shard::clusterName).distinct().count() != shards.size()) {
            throw new IllegalArgumentException("Shard topology must contain a nonempty list of distinct shards");
        }
    }

    public static ShardTopology fromNamesAndRegions(String clusterNames, String regions) {
        Objects.requireNonNull(clusterNames, "Shard cluster names are required");
        Objects.requireNonNull(regions, "Shard regions are required");
        String[] names = clusterNames.split(",", -1);
        String[] regionNames = regions.split(",", -1);
        if (names.length != regionNames.length) {
            throw new IllegalArgumentException("Each shard must have a region");
        }
        return new ShardTopology(IntStream.range(0, names.length)
                .mapToObj(index -> new Shard(names[index], regionNames[index])).toList());
    }
}
