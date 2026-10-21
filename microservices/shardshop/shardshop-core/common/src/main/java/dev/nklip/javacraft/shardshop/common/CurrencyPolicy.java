package dev.nklip.javacraft.shardshop.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/** Authoritative seller-country currency and exact NUMERIC(19,2) monetary policy. */
public final class CurrencyPolicy {
    public static final List<String> SUPPORTED_CURRENCIES = List.of("CNY", "EUR", "GBP", "JPY", "KRW", "USD", "VND");
    private static final Set<String> EU_COUNTRIES = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI",
            "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");

    private CurrencyPolicy() {
    }

    public static List<String> currencies(String region, String countryCode) {
        if ("US".equals(region) && "US".equals(countryCode)) {
            return List.of("USD");
        }
        if ("EU".equals(region) && countryCode != null && EU_COUNTRIES.contains(countryCode)) {
            return List.of("EUR", "GBP");
        }
        if ("ASIA".equals(region) && countryCode != null) {
            return switch (countryCode) {
                case "CN" -> List.of("CNY");
                case "VN" -> List.of("VND");
                case "KR" -> List.of("KRW");
                case "JP" -> List.of("JPY");
                default -> throw new IllegalArgumentException("Unmapped seller country");
            };
        }
        throw new IllegalArgumentException("Unmapped seller region and country");
    }

    public static boolean permits(String region, String countryCode, String currency) {
        try {
            return currency != null && currencies(region, countryCode).contains(currency);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    public static int fractionDigits(String currency) {
        if (currency == null || !SUPPORTED_CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("Unsupported currency");
        }
        return switch (currency) {
            case "JPY", "KRW", "VND" -> 0;
            default -> 2;
        };
    }

    /** Signed amounts are allowed for confirmed losses; callers enforce nonnegative prices. */
    public static boolean validAmount(BigDecimal amount, String currency) {
        if (amount == null) {
            return false;
        }
        try {
            BigDecimal fixed = amount.setScale(2, RoundingMode.UNNECESSARY);
            amount.setScale(fractionDigits(currency), RoundingMode.UNNECESSARY);
            return fixed.precision() <= 19;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return false;
        }
    }
}
