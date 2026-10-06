package dev.nklip.javacraft.shardshop.workload.producer;

import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.Sha256;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Selects EUR when the unsigned SHA-256 digest of an issued order ID, modulo 100,
 * is below the configured rejection percentage; otherwise selects USD.
 */
public final class CurrencySelector {

    private static final BigInteger BUCKET_COUNT = BigInteger.valueOf(100);

    private final IdParser idParser;
    private final int rejectedOrderPercent;

    public CurrencySelector() {
        this(10);
    }

    public CurrencySelector(int rejectedOrderPercent) {
        this(new IdParser(), rejectedOrderPercent);
    }

    public CurrencySelector(IdParser idParser, int rejectedOrderPercent) {
        this.idParser = Objects.requireNonNull(idParser, "ID parser must not be null");
        if (rejectedOrderPercent < 0 || rejectedOrderPercent > 100) {
            throw new IllegalArgumentException("Rejected order percentage must be between 0 and 100");
        }
        this.rejectedOrderPercent = rejectedOrderPercent;
    }

    public String select(String orderId) {
        // Validate only; retain the exact original text as the hash input.
        idParser.parse(orderId);
        int bucket = Sha256.unsignedDigest(orderId).mod(BUCKET_COUNT).intValue();
        return bucket < rejectedOrderPercent ? "EUR" : "USD";
    }
}
