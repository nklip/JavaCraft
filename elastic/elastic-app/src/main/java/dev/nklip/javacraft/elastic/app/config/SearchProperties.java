package dev.nklip.javacraft.elastic.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised tuning knobs for QueryFactory implementations.
 * Values live in application.yaml under the {@code search:} key.
 */
@ConfigurationProperties(prefix = "search")
public record SearchProperties(
        FuzzyProperties fuzzy,
        IntervalProperties interval,
        SpanProperties span
) {
    /** Maximum edit distance allowed for fuzzy matching. */
    public record FuzzyProperties(String fuzziness) {}

    /** Maximum number of intervening unmatched positions permitted between interval terms. */
    public record IntervalProperties(int maxGaps) {}

    /** Maximum number of intervening unmatched positions permitted between span terms. */
    public record SpanProperties(int slop) {}
}
