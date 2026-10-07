package dev.nklip.javacraft.shardshop.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonFieldsTest {
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    @Test
    void readsExactStringsAndIds() throws IOException {
        JsonNode node = json.readTree("{\"id\":\"9223372036854775807\",\"name\":\"  Market 🛒  \",\"region\":\"EU\"}");
        JsonFields.requireFields(node, "id", "name", "region");
        assertEquals("9223372036854775807", JsonFields.id(node, "id"));
        assertEquals("  Market 🛒  ", JsonFields.text(node, "name"));
        assertEquals("EU", JsonFields.matchingText(node, "region", "US|EU|ASIA"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{\"name\":null}", "{\"name\":\"x\",\"extra\":true}"})
    void rejectsUnexpectedObjectShapes(String raw) throws IOException {
        JsonNode node = json.readTree(raw);
        assertThrows(IllegalArgumentException.class, () -> JsonFields.requireFields(node, "name", "id"));
    }

    @Test
    void rejectsAbsentObjects() {
        assertThrows(IllegalArgumentException.class, () -> JsonFields.requireFields(null, "name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":null}", "{\"id\":9007199254740993}", "{\"id\":\"01\"}"})
    void rejectsMissingNumericAndInvalidIds(String raw) throws IOException {
        JsonNode node = json.readTree(raw);
        assertThrows(IllegalArgumentException.class, () -> JsonFields.id(node, "id"));
    }

    @Test
    void rejectsInvalidTextWithoutDisclosingIt() {
        JsonNode node = json.createObjectNode().put("region", "secret");
        assertEquals("Invalid JSON string", assertThrows(IllegalArgumentException.class,
                () -> JsonFields.matchingText(node, "region", "US|EU|ASIA")).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "0.01", "-0.01", "12.34", "-12.34", "99999999999999999.99", "-99999999999999999.99"})
    void readsCanonicalSignedMoneyAndAdditionalCurrenciesWithoutPrecisionLoss(String amount) {
        JsonNode node = json.createObjectNode().set("profits", json.createObjectNode()
                .put("USD", amount).put("EUR", "0.00").put("GBP", "42.00"));
        Map<String, String> result = JsonFields.signedMoneyMap(node, "profits", "USD", "EUR");
        assertEquals(Map.of("USD", amount, "EUR", "0.00", "GBP", "42.00"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.put("USD", "0.01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-0.00", "+0.00", "+1.00", "01.00", "-01.00", "1.0", "1.000", "1e2", " 1.00",
            "1.00\n", "100000000000000000.00", "-100000000000000000.00"})
    void rejectsNoncanonicalSignedMoney(String amount) {
        JsonNode node = json.createObjectNode().set("profits", json.createObjectNode().put("USD", amount).put("EUR", "0.00"));
        assertThrows(IllegalArgumentException.class, () -> JsonFields.signedMoneyMap(node, "profits", "USD", "EUR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"profits\":null}", "{\"profits\":[]}", "{\"profits\":{}}",
            "{\"profits\":{\"USD\":\"0.00\"}}", "{\"profits\":{\"EUR\":\"0.00\"}}",
            "{\"profits\":{\"USD\":0,\"EUR\":\"0.00\"}}",
            "{\"profits\":{\"USD\":\"0.00\",\"EUR\":null}}",
            "{\"profits\":{\"USD\":\"0.00\",\"EUR\":\"0.00\",\"usd\":\"0.00\"}}",
            "{\"profits\":{\"USD\":\"0.00\",\"EUR\":\"0.00\",\"USDX\":\"0.00\"}}",
            "{\"profits\":{\"USD\":\"0.00\",\"EUR\":\"0.00\",\"GBP\":\"-0.00\"}}"})
    void rejectsMissingCurrenciesInvalidKeysAndNonstrings(String raw) throws IOException {
        JsonNode node = json.readTree(raw);
        assertThrows(IllegalArgumentException.class, () -> JsonFields.signedMoneyMap(node, "profits", "USD", "EUR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "2.0", "2e0"})
    void acceptsExactlyIntegralNumbers(String number) throws IOException {
        assertEquals(2, JsonFields.nonnegativeInteger(json.readTree("{\"stock\":" + number + "}"), "stock"));
    }

    @Test
    void acceptsIntegerBoundaries() {
        assertEquals(0, JsonFields.nonnegativeInteger(json.createObjectNode().put("stock", 0), "stock"));
        assertEquals(Integer.MAX_VALUE,
                JsonFields.nonnegativeInteger(json.createObjectNode().put("stock", Integer.MAX_VALUE), "stock"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"stock\":\"2\"}", "{\"stock\":null}", "{\"stock\":-1}",
            "{\"stock\":2.5}", "{\"stock\":2147483648}", "{\"stock\":2.00000000000000000001}"})
    void rejectsNonIntegersWithoutRounding(String raw) throws IOException {
        JsonNode node = json.readTree(raw);
        assertThrows(IllegalArgumentException.class, () -> JsonFields.nonnegativeInteger(node, "stock"));
    }
}
