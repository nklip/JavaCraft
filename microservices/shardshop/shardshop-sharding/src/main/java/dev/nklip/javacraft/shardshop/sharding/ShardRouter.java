package dev.nklip.javacraft.shardshop.sharding;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Routing contract version 2: a positive Snowflake ID belongs to shard
 * {@code unsignedBigEndian(SHA-256(UTF-8(decimal ID))) mod 3}.
 * <p>
 * The hash and the divisor are frozen; changing either moves existing rows and needs a data migration.
 */
public final class ShardRouter {

    private static final BigInteger SHARD_COUNT = BigInteger.valueOf(3);

    public Shard route(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Shard routing requires a positive ID, but was " + id);
        }
        byte[] digest = sha256().digest(Long.toString(id).getBytes(StandardCharsets.UTF_8));
        return Shard.values()[new BigInteger(1, digest).mod(SHARD_COUNT).intValue()];
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
