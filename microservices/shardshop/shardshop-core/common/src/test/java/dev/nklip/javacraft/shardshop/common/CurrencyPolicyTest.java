package dev.nklip.javacraft.shardshop.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CurrencyPolicyTest {
    @Test
    void mapsAllConfiguredCountriesWithoutDatasetOrEntityIdentity() {
        assertEquals(List.of("USD"), CurrencyPolicy.currencies("US", "US"));
        for (String country : List.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
                "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE")) {
            assertEquals(List.of("EUR", "GBP"), CurrencyPolicy.currencies("EU", country));
        }
        for (String[] mapping : List.of(new String[]{"CN", "CNY"}, new String[]{"VN", "VND"},
                new String[]{"KR", "KRW"}, new String[]{"JP", "JPY"})) {
            assertEquals(List.of(mapping[1]), CurrencyPolicy.currencies("ASIA", mapping[0]));
        }
        assertThrows(UnsupportedOperationException.class, () -> CurrencyPolicy.SUPPORTED_CURRENCIES.add("AUD"));
        assertThrows(UnsupportedOperationException.class, () -> CurrencyPolicy.currencies("EU", "FR").add("USD"));
    }

    @Test
    void rejectsUnmappedAndMismatchedCountriesAndAllForbiddenCurrencyCombinations() {
        for (String[] location : List.of(new String[]{"US", "US"}, new String[]{"EU", "FR"},
                new String[]{"ASIA", "CN"}, new String[]{"ASIA", "VN"},
                new String[]{"ASIA", "KR"}, new String[]{"ASIA", "JP"})) {
            for (String currency : CurrencyPolicy.SUPPORTED_CURRENCIES) {
                assertEquals(CurrencyPolicy.currencies(location[0], location[1]).contains(currency),
                        CurrencyPolicy.permits(location[0], location[1], currency));
            }
        }
        for (String[] invalid : List.of(new String[]{"US", "JP"}, new String[]{"EU", "US"},
                new String[]{"EU", "GB"}, new String[]{"ASIA", "DE"}, new String[]{"ASIA", "IN"},
                new String[]{"AU", "AU"}, new String[]{null, "US"}, new String[]{"US", null},
                new String[]{"us", "US"}, new String[]{"US", "us"})) {
            assertThrows(IllegalArgumentException.class, () -> CurrencyPolicy.currencies(invalid[0], invalid[1]));
            assertFalse(CurrencyPolicy.permits(invalid[0], invalid[1], "USD"));
        }
        assertFalse(CurrencyPolicy.permits("US", "US", null));
        assertFalse(CurrencyPolicy.permits("US", "US", "AUD"));
    }

    @Test
    void enforcesQualifiedMinorUnitsAndTheExistingNumericStorageBoundWithoutRounding() {
        assertEquals(List.of("CNY", "EUR", "GBP", "JPY", "KRW", "USD", "VND"), CurrencyPolicy.SUPPORTED_CURRENCIES);
        for (String currency : CurrencyPolicy.SUPPORTED_CURRENCIES) {
            boolean integral = List.of("JPY", "KRW", "VND").contains(currency);
            assertEquals(integral ? 0 : 2, CurrencyPolicy.fractionDigits(currency));
            assertTrue(CurrencyPolicy.validAmount(new BigDecimal("19.00"), currency));
            assertTrue(CurrencyPolicy.validAmount(new BigDecimal("-19.00"), currency));
            assertTrue(CurrencyPolicy.validAmount(BigDecimal.ZERO, currency));
            assertTrue(CurrencyPolicy.validAmount(new BigDecimal("99999999999999999.00"), currency));
            assertFalse(CurrencyPolicy.validAmount(new BigDecimal("100000000000000000.00"), currency));
            assertFalse(CurrencyPolicy.validAmount(new BigDecimal("0.001"), currency));
            assertEquals(!integral, CurrencyPolicy.validAmount(new BigDecimal("19.01"), currency));
            assertEquals(!integral, CurrencyPolicy.validAmount(new BigDecimal("-0.01"), currency));
            assertEquals(!integral, CurrencyPolicy.validAmount(new BigDecimal("99999999999999999.99"), currency));
        }
        assertFalse(CurrencyPolicy.validAmount(null, "USD"));
        assertFalse(CurrencyPolicy.validAmount(BigDecimal.ONE, "AUD"));
        assertFalse(CurrencyPolicy.validAmount(BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> CurrencyPolicy.fractionDigits("AUD"));
        assertThrows(IllegalArgumentException.class, () -> CurrencyPolicy.fractionDigits(null));
    }
}
