package dev.nklip.javacraft.shardshop.common;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Hashes unchanged UTF-8 text and interprets the complete digest as an unsigned integer.
 * Callers own any validation of the input's domain format.
 */
public final class Sha256 {

    private Sha256() {
        // Utility class; no instances are needed.
    }

    public static BigInteger unsignedDigest(String text) {
        Objects.requireNonNull(text, "Hash input must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
