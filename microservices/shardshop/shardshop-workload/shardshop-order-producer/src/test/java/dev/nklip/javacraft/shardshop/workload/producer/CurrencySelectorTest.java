package dev.nklip.javacraft.shardshop.workload.producer;

import dev.nklip.javacraft.shardshop.common.IdParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrencySelectorTest {

    private final CurrencySelector selector = new CurrencySelector();

    @ParameterizedTest
    @CsvSource({
            "880803840000004605, EUR",
            "880803840000004432, EUR",
            "880803840000004254, USD",
            "880803840000004299, USD"
    })
    void selectsDefaultCurrencyAtPublishedBoundaries(String orderId, String expectedCurrency) {
        assertEquals(expectedCurrency, selector.select(orderId));
    }

    // Buckets were calculated independently with Python hashlib over all 32 digest bytes.
    @ParameterizedTest
    @CsvSource({
            "1, 15",
            "2, 61",
            "9007199254740992, 6",
            "9007199254740993, 41",
            "9223372036854775807, 93",
            "880803840000004605, 0",
            "880803840000004432, 9",
            "880803840000004254, 10",
            "880803840000004299, 99"
    })
    void usesTheFullUnsignedDigestAndExactIdText(String orderId, int bucket) {
        assertEquals("USD", new CurrencySelector(bucket).select(orderId));
        assertEquals("EUR", new CurrencySelector(bucket + 1).select(orderId));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1", "9007199254740993", "9223372036854775807",
            "880803840000004605", "880803840000004432", "880803840000004254", "880803840000004299"
    })
    void supportsZeroAndOneHundredPercent(String orderId) {
        assertEquals("USD", new CurrencySelector(0).select(orderId));
        assertEquals("EUR", new CurrencySelector(100).select(orderId));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void rejectsPercentagesOutsideTheAllowedRange(int rejectedOrderPercent) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new CurrencySelector(rejectedOrderPercent));

        assertEquals("Rejected order percentage must be between 0 and 100", exception.getMessage());
    }

    @Test
    void selectsCurrencyWithAnInjectedIdParser() {
        CurrencySelector injected = new CurrencySelector(new IdParser(), 10);

        assertEquals("EUR", injected.select("880803840000004432"));
        assertEquals("USD", injected.select("880803840000004254"));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> injected.select("0"));
        assertEquals("ID must be a canonical decimal string in 1..9223372036854775807", exception.getMessage());
    }

    @Test
    void rejectsAMissingIdParser() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> new CurrencySelector(null, 10));

        assertEquals("ID parser must not be null", exception.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "0", "-1", "+1", "01", " 1", "1 ", "1\n", "1\r\n", "\t1", "1\t", "1.0", "1e0",
            "9223372036854775808", "10000000000000000000", "\u0661", "\uff11"
    })
    void rejectsInvalidIds(String orderId) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> selector.select(orderId));

        assertEquals("ID must be a canonical decimal string in 1..9223372036854775807", exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"880803840000004432, EUR", "880803840000004254, USD"})
    void preservesCurrencyAcrossRepeatedSelections(String orderId, String expectedCurrency) {
        for (int attempt = 0; attempt < 10; attempt++) {
            assertEquals(expectedCurrency, selector.select(orderId));
            assertEquals(expectedCurrency, new CurrencySelector().select(orderId));
        }
    }
}
