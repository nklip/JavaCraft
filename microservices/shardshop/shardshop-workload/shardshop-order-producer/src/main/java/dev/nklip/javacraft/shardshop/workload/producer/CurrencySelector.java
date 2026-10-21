package dev.nklip.javacraft.shardshop.workload.producer;

import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.Sha256;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Validates the selection setting. {@code -1} is the buyer-region currency mix.
 * {@code 0..100} is the explicit USD/EUR test distribution from the issued order ID.
 */
public final class CurrencySelector {

    private static final BigInteger BUCKET_COUNT = BigInteger.valueOf(100);

    private final IdParser idParser;
    private final int rejectedOrderPercent;

    public CurrencySelector() {
        this(-1);
    }

    public CurrencySelector(int rejectedOrderPercent) {
        this(new IdParser(), rejectedOrderPercent);
    }

    public CurrencySelector(IdParser idParser, int rejectedOrderPercent) {
        this.idParser = Objects.requireNonNull(idParser, "ID parser must not be null");
        if (rejectedOrderPercent < -1 || rejectedOrderPercent > 100) {
            throw new IllegalArgumentException("Rejected order percentage must be -1 (normal traffic) or between 0 and 100");
        }
        this.rejectedOrderPercent = rejectedOrderPercent;
    }

    public String mode() {
        return normalTraffic() ? "regional-v1" : "usd-eur-" + rejectedOrderPercent;
    }

    public boolean normalTraffic() {
        return rejectedOrderPercent == -1;
    }

    /** Only for the explicit USD/EUR distribution; normal traffic uses the planner's regional schedule. */
    public String select(String orderId) {
        // Validate only; retain the exact original text as the hash input.
        idParser.parse(orderId);
        if (normalTraffic()) {
            throw new IllegalStateException("Normal traffic uses the buyer-region currency mix");
        }
        int bucket = Sha256.unsignedDigest(orderId).mod(BUCKET_COUNT).intValue();
        return bucket < rejectedOrderPercent ? "EUR" : "USD";
    }
}
