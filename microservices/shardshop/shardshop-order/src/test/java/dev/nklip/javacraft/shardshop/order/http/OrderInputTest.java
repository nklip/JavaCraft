package dev.nklip.javacraft.shardshop.order.http;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.AllocationRequest;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderInputTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String BUYER = """
            {"firstName":"Jane","surname":"Smith","email":"jane.smith@example.org","phone":"+12025550123",
             "address":{"line1":"1 Main St","city":"Paris","postalCode":"75001","countryCode":"FR"},"region":"EU"}
            """;
    private final OrderInput input = new OrderInput();

    @ParameterizedTest
    @ValueSource(strings = {"", " \\t\\n", "\\u0000", "\\ud800", "\\udfff", "\\udc00\\ud800"})
    void namesAndRunNamesRejectBlankNulAndUnpairedSurrogates(String text) {
        invalid(() -> input.buyer(bytes(BUYER.replace("Jane", text)), headers()));
        invalid(() -> input.allocation(bytes("{\"runName\":\"" + text + "\",\"requestOrdinal\":\"0\"}"), headers()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "[]", "{}", "{", "true", "{\"currency\":\"EUR\",}"})
    void rejectsMalformedDocuments(String body) {
        invalid(() -> input.buyer(bytes(body), headers()));
        invalid(() -> input.allocation(bytes(body), headers()));
        invalid(() -> input.order(bytes(body), headers()));
    }

    @Test
    void countsUnicodeScalarValuesAndPreservesText() {
        String name = "🚀".repeat(200);
        String body = BUYER.replace("Jane", name);
        assertEquals(name, input.buyer(bytes(body), headers()).firstName());
        invalid(() -> input.buyer(bytes(body.replace(name, name + "🚀")), headers()));
        assertEquals(new AllocationRequest(" run 🚀 ", 0), input.allocation(
                bytes("{\"runName\":\" run \\ud83d\\ude80 \",\"requestOrdinal\":\"0\"}"), headers()));
        invalid(() -> input.buyer(bytes(BUYER.replace("Paris", "A".repeat(101))), headers()));
        invalid(() -> input.buyer(bytes(BUYER.replace("75001", "A".repeat(21))), headers()));
        invalid(() -> input.buyer(bytes(BUYER.replace("1 Main St", "A".repeat(201))), headers()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a@example", "a..b@example.org", ".a@example.org", "a.@example.org", "a@-example.org",
            "a@example-.org", "a@exam_ple.org", "é@example.org", "a@exämple.org", "a@example.org\\n"})
    void enforcesAsciiDotAtomEmail(String email) {
        invalid(() -> input.buyer(bytes(BUYER.replace("jane.smith@example.org", email)), headers()));
    }

    @Test
    void enforcesEmailLengthLocalPartAndDnsLabelBounds() {
        String longest = "a".repeat(64) + "@" + String.join(".", "a".repeat(63), "b".repeat(63), "c".repeat(61));
        assertEquals(longest, input.buyer(bytes(BUYER.replace("jane.smith@example.org", longest)), headers()).email());
        for (String email : List.of("a".repeat(65) + "@example.org", longest + "x", "a@" + "b".repeat(64) + ".org")) {
            invalid(() -> input.buyer(bytes(BUYER.replace("jane.smith@example.org", email)), headers()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567", "+123456", "+1234567890123456", "+1 2025550123", "+１２３４５６７", "+1234567\\n"})
    void enforcesInternationalPhoneSyntax(String phone) {
        invalid(() -> input.buyer(bytes(BUYER.replace("+12025550123", phone)), headers()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00", "01", "-1", "1.0", "1e0", "1\\n", "9223372036854775808"})
    void ordinalsAreCanonicalNonnegativeLongText(String ordinal) {
        invalid(() -> input.allocation(bytes("{\"runName\":\"run\",\"requestOrdinal\":\"" + ordinal + "\"}"), headers()));
    }

    @Test
    void rejectsEmptyTooManyAndRepeatedItemPairsButAllowsOneProductFromDifferentSellers() throws Exception {
        Map<String, Object> item = Map.of("sellerId", "1", "productId", "2", "quantity", 1);
        invalid(() -> input.order(JSON.writeValueAsBytes(Map.of("currency", "USD", "items", List.of())), headers()));
        invalid(() -> input.order(JSON.writeValueAsBytes(Map.of("currency", "USD", "items", java.util.Collections.nCopies(1001, item))), headers()));
        invalid(() -> input.order(JSON.writeValueAsBytes(Map.of("currency", "USD", "items", List.of(item, item))), headers()));
        var otherSeller = Map.of("sellerId", "3", "productId", "2", "quantity", 2);
        assertEquals(2, input.order(JSON.writeValueAsBytes(Map.of("currency", "USD", "items", List.of(item, otherSeller))), headers()).items().size());
    }

    @Test
    void acceptsMaximumItemCount() throws Exception {
        var items = java.util.stream.LongStream.rangeClosed(1, 1000)
                .mapToObj(product -> Map.of("sellerId", "1", "productId", Long.toString(product), "quantity", 1)).toList();
        assertEquals(1000, input.order(JSON.writeValueAsBytes(Map.of("currency", "USD", "items", items)), headers()).items().size());
    }

    private HttpHeaders headers() {
        HttpHeaders result = mock(HttpHeaders.class);
        when(result.getRequestHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(List.of("application/json"));
        return result;
    }

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void invalid(org.junit.jupiter.api.function.Executable request) {
        assertEquals(OrderFailure.Reason.INVALID_REQUEST, assertThrows(OrderFailure.class, request).reason());
    }
}
