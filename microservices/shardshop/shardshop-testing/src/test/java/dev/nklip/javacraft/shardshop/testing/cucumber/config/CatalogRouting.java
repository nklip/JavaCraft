package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The placement rules of ARCHITECTURE section 3 and the product README, calculated again from the JDK: the scenarios
 * do not use the routing classes of the applications that they test.
 */
public final class CatalogRouting {

    private CatalogRouting() {
    }

    /** The UTF-8 text's SHA-256 digest as an unsigned big-endian integer. */
    public static BigInteger digest(String text) {
        try {
            return new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Routing contract version 2: the digest of the decimal seller ID modulo the number of shards. */
    public static int sellerShard(String sellerId, int shards) {
        return digest(sellerId).mod(BigInteger.valueOf(shards)).intValue();
    }

    /** The fixed home of a seller creation key: the digest of {@code seller\0region\0key} among the region's shards. */
    public static int keyShard(List<String> regions, String region, String key) {
        List<Integer> eligible = new ArrayList<>();
        for (int index = 0; index < regions.size(); index++) {
            if (regions.get(index).equals(region)) {
                eligible.add(index);
            }
        }
        return eligible.get(digest("seller\0" + region + "\0" + key).mod(BigInteger.valueOf(eligible.size())).intValue());
    }

    /** The transaction advisory lock of a creation: the low 64 bits of {@code collection\0scope\0key} as a signed long. */
    public static long creationLock(String collection, String scope, String key) {
        return digest(collection + "\0" + scope + "\0" + key).longValue();
    }
}
