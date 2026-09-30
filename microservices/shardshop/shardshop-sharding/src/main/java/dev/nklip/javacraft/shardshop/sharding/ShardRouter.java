package dev.nklip.javacraft.shardshop.sharding;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * A positive Snowflake ID belongs to the configured shard at index
 * {@code unsignedBigEndian(SHA-256(UTF-8(decimal ID))) mod shardCount}.
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
        byte[] digest = sha256().digest(Long.toString(id).getBytes(StandardCharsets.UTF_8));
        return topology.shards().get(new BigInteger(1, digest).mod(shardCount).intValue());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
