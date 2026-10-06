package dev.nklip.javacraft.shardshop.sharding;

import dev.nklip.javacraft.shardshop.common.Sha256;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A positive Snowflake ID belongs to the configured shard at index
 * {@code unsignedBigEndian(SHA-256(UTF-8(decimal ID))) mod shardCount}.
 * Sellers and buyers inherit that shard's region; products route by their seller ID.
 * A buyer's home region does not restrict which sellers an order can reference.
 * <p>
 * The hash remains routing contract version 2. The topology fixes shard count and ordering
 * for this router's lifetime; changing either moves existing rows and needs a data migration.
 */
public final class ShardRouter {

    private final ShardTopology topology;
    private final BigInteger shardCount;

    public ShardRouter(ShardTopology topology) {
        this.topology = Objects.requireNonNull(topology, "Shard topology is required");
        this.shardCount = BigInteger.valueOf(topology.shards().size());
    }

    public Shard route(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Shard routing requires a positive ID, but was " + id);
        }
        return topology.shards().get(Sha256.unsignedDigest(Long.toString(id)).mod(shardCount).intValue());
    }
}
