package dev.nklip.javacraft.shardshop.product.http;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.ProductCreation;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.SellerCreation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class CatalogInputTest {
    private static final String PRODUCT = "{\"name\":\"Mug\",\"description\":\"A mug\",\"price\":\"19.95\","
            + "\"unitCost\":\"12.45\",\"currency\":\"EUR\",\"initialStock\":%s}";
    private final CatalogInput input = new CatalogInput();

    @ParameterizedTest
    @ValueSource(strings = {"US", "EU", "ASIA"})
    void preservesCompanyTextAndRegion(String region) {
        assertEquals(new SellerCreation("  Market 🛒  ", region), input.seller(
                bytes("{\"companyName\":\"  Market 🛒  \",\"region\":\"" + region + "\"}"), jsonHeaders()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "2.0", "2e0", "2.0000000000000000000000000"})
    void parsesIntegralStockExactly(String number) {
        assertEquals(new ProductCreation("Mug", "A mug", "19.95", "12.45", "EUR", 2),
                input.product(bytes(PRODUCT.formatted(number)), jsonHeaders()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2147483647"})
    void acceptsStockBoundaries(String number) {
        assertEquals(Integer.parseInt(number), input.product(bytes(PRODUCT.formatted(number)), jsonHeaders()).initialStock());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2\"", "true", "null", "-1", "2.5", "2147483648", "2.00000000000000000001", "1e10000"})
    void rejectsNonintegerOrOutOfRangeStock(String number) {
        assertInvalid(() -> input.product(bytes(PRODUCT.formatted(number)), jsonHeaders()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "[]", "{}", "{", "{\"companyName\":true,\"region\":\"US\"}",
            "{\"companyName\":\"x\",\"region\":null}", "{\"companyName\":\"x\",\"region\":2}",
            "{\"companyName\":\"x\",\"region\":\"US\",\"sellerId\":\"1\"}",
            "{\"companyName\":\"x\",\"companyName\":\"x\",\"region\":\"US\"}",
            "{\"companyName\":\"x\",\"region\":\"US\"} {}",
            "{\"companyName\":\"x\",\"region\":\"US\",}", "{'companyName':'x','region':'US'}"})
    void rejectsMalformedSellerDocuments(String document) {
        assertInvalid(() -> input.seller(bytes(document), jsonHeaders()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "[]", "{}", "{", "{\"name\":\"x\"}",
            "{\"name\":\"Mug\",\"description\":\"A mug\",\"price\":19.95,\"unitCost\":\"12.45\",\"currency\":\"EUR\",\"initialStock\":2}"})
    void rejectsMalformedProductDocuments(String document) {
        assertInvalid(() -> input.product(bytes(document), jsonHeaders()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sellerId", "productId", "region", "stock", "profitsEarned", "shard"})
    void rejectsServiceOwnedOrUnknownProductFields(String field) {
        String document = PRODUCT.formatted("2").replace("}", ",\"" + field + "\":null}");
        assertInvalid(() -> input.product(bytes(document), jsonHeaders()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.00", "-1.00", "+1.00", "01.00", "1.0", "1.000", "1e2", "1.00\\n", "100000000000000000.00"})
    void rejectsNoncanonicalPriceAndCost(String money) {
        assertInvalid(() -> input.product(bytes(PRODUCT.formatted("2").replace("19.95", money)), jsonHeaders()));
        assertInvalid(() -> input.product(bytes(PRODUCT.formatted("2").replace("12.45", money)), jsonHeaders()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "0.01", "99999999999999999.99"})
    void preservesExactMoney(String money) {
        ProductCreation result = input.product(bytes(PRODUCT.formatted("2")
                .replace("19.95", money).replace("12.45", money)), jsonHeaders());
        assertEquals(money, result.price());
        assertEquals(money, result.unitCost());
    }

    @ParameterizedTest
    @ValueSource(strings = {"usd", "US", "USDD", "USD\\n", " USD", "€UR"})
    void rejectsInvalidCurrency(String currency) {
        assertInvalid(() -> input.product(bytes(PRODUCT.formatted("2").replace("EUR", currency)), jsonHeaders()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "us", "EU27", "US ", "US\\n", "AFRICA"})
    void rejectsUnsupportedRegions(String region) {
        assertInvalid(() -> input.seller(bytes("{\"companyName\":\"x\",\"region\":\"" + region + "\"}"), jsonHeaders()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\\t", "\\u001c", "\\u0085", "\\u00a0", "\\u2007", "\\u202f", "\\u3000",
            "\\u0000", "a\\u0000b", "\\ud800", "\\udfff", "a\\ud800b"})
    void rejectsBlankOrNonscalarText(String text) {
        assertInvalid(() -> input.seller(bytes("{\"companyName\":\"" + text + "\",\"region\":\"US\"}"), jsonHeaders()));
        assertInvalid(() -> input.product(bytes(PRODUCT.formatted("2").replace("Mug", text)), jsonHeaders()));
        assertInvalid(() -> input.product(bytes(PRODUCT.formatted("2").replace("A mug", text)), jsonHeaders()));
    }

    @Test
    void countsUnicodeScalarsAndPreservesTextWithoutNormalization() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        String name = "🛒".repeat(200);
        String description = "🛒".repeat(2000);
        String document = json.writeValueAsString(new ProductCreation(name, description, "0.00", "0.00", "USD", 0));
        ProductCreation result = input.product(bytes(document), jsonHeaders());
        assertEquals(name, result.name());
        assertEquals(description, result.description());
        assertInvalid(() -> input.product(bytes(document.replace(name, name + "x")), jsonHeaders()));
        assertInvalid(() -> input.product(bytes(document.replace(description, description + "x")), jsonHeaders()));
        assertEquals("e\u0301", input.seller(bytes("{\"companyName\":\"é\",\"region\":\"US\"}"), jsonHeaders()).companyName());
    }

    @Test
    void rejectsMissingBodyAndMalformedUtf8() {
        assertInvalid(() -> input.seller(null, jsonHeaders()));
        assertInvalid(() -> input.seller(new byte[]{(byte) 0xc3, 0x28}, jsonHeaders()));
    }

    @Test
    void requiresConcreteJsonMediaType() {
        HttpHeaders headers = jsonHeaders();
        when(headers.getMediaType()).thenReturn(null);
        byte[] body = bytes("{\"companyName\":\"x\",\"region\":\"US\"}");
        assertInvalid(() -> input.seller(body, headers));
        for (String type : List.of("text/plain", "application/xml", "*/*", "application/*", "text/json")) {
            when(headers.getMediaType()).thenReturn(MediaType.valueOf(type));
            assertInvalid(() -> input.seller(body, headers));
        }
        when(headers.getMediaType()).thenReturn(MediaType.valueOf("application/json;charset=UTF-8"));
        assertEquals("x", input.seller(body, headers).companyName());
    }

    @Test
    void rejectsMalformedMediaTypeWithoutLeakingItsValue() {
        HttpHeaders headers = jsonHeaders();
        when(headers.getMediaType()).thenThrow(new jakarta.ws.rs.BadRequestException("private header value"));
        assertInvalid(() -> input.seller(bytes("{}"), headers));
        doThrow(new IllegalArgumentException("private header value")).when(headers).getMediaType();
        assertInvalid(() -> input.seller(bytes("{}"), headers));
    }

    @Test
    void requiresExactlyOneRawContentTypeHeader() {
        HttpHeaders headers = jsonHeaders();
        when(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(null);
        assertInvalid(() -> input.seller(bytes("{}"), headers));
        when(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(List.of());
        assertInvalid(() -> input.seller(bytes("{}"), headers));
        when(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(List.of("application/json", "application/json"));
        assertInvalid(() -> input.seller(bytes("{}"), headers));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json; charset", "application/json;", "application/json; charset=",
            "application/json; charset=\"UTF-8", "application/json; charset=\"UTF-8\" extra", "application/json,application/json",
            "application/json; charset =UTF-8", "application/json; charset=UTF-8\n", "application/json; charset=\"a\r\""})
    void rejectsRawMediaSyntaxEvenWhenTheFrameworkParserIsLenient(String rawType) {
        HttpHeaders headers = jsonHeaders();
        when(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(List.of(rawType));
        assertInvalid(() -> input.seller(bytes("{\"companyName\":\"x\",\"region\":\"US\"}"), headers));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "APPLICATION/JSON", "application/json; charset=UTF-8",
            "application/json;charset=\"UTF-8\"", "application/json; extension=custom; charset=UTF-8",
            " application/json\t; extension=\"quoted ; parameter\\\" value\"\t", "application/json; extension=\"\""})
    void acceptsValidTokenAndQuotedMediaParameters(String rawType) {
        HttpHeaders headers = jsonHeaders();
        when(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(List.of(rawType));
        assertEquals(new SellerCreation("x", "US"),
                input.seller(bytes("{\"companyName\":\"x\",\"region\":\"US\"}"), headers));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "9007199254740993", "9223372036854775807"})
    void readsCanonicalIdsWithoutPrecisionLoss(String id) {
        assertEquals(Long.parseLong(id), input.id(id));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "01", "-1", "+1", "1.0", "1e0", " 1", "1\n", "１", "9223372036854775808"})
    void rejectsNoncanonicalIds(String id) {
        assertInvalid(() -> input.id(id));
    }

    @Test
    void preservesOpaqueCreationKeys() {
        assertEquals("Ab.1_-:Z", input.key("Ab.1_-:Z"));
        assertEquals("a".repeat(128), input.key("a".repeat(128)));
        assertInvalid(() -> input.key("a".repeat(129)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-key", ".key", "_key", ":key", "key/key", " key", "key ", "key\n", "key,key", "キー"})
    void rejectsMalformedCreationKeys(String key) {
        assertInvalid(() -> input.key(key));
    }

    @Test
    void requiresOneIdempotencyHeader() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeader("Idempotency-Key")).thenReturn(null);
        assertInvalid(() -> input.headerKey(headers));
        when(headers.getRequestHeader("Idempotency-Key")).thenReturn(List.of());
        assertInvalid(() -> input.headerKey(headers));
        when(headers.getRequestHeader("Idempotency-Key")).thenReturn(List.of("key", "key"));
        assertInvalid(() -> input.headerKey(headers));
        when(headers.getRequestHeader("Idempotency-Key")).thenReturn(List.of("Key"));
        assertEquals("Key", input.headerKey(headers));
        when(headers.getRequestHeader("Idempotency-Key")).thenReturn(List.of("key,key"));
        assertInvalid(() -> input.headerKey(headers));
    }

    @Test
    void rejectsAllQueryParametersOutsideSellerKeyLookup() {
        UriInfo uri = uri(new MultivaluedHashMap<>());
        assertDoesNotThrow(() -> input.noQuery(uri));
        assertInvalid(() -> input.noQuery(uri(new MultivaluedHashMap<>(java.util.Map.of("shard", "a")))));
    }

    @Test
    void requiresExactlyOneRegionQuery() {
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        UriInfo uri = uri(query);
        assertInvalid(() -> input.regionQuery(uri));
        query.add("other", "US");
        assertInvalid(() -> input.regionQuery(uri));
        query.add("region", "US");
        assertInvalid(() -> input.regionQuery(uri));
        query.remove("other");
        assertEquals("US", input.regionQuery(uri));
        query.add("region", "US");
        assertInvalid(() -> input.regionQuery(uri));
        query.put("region", List.of());
        assertInvalid(() -> input.regionQuery(uri));
        query.put("region", List.of("us"));
        assertInvalid(() -> input.regionQuery(uri));
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(List.of(MediaType.APPLICATION_JSON));
        when(headers.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        return headers;
    }

    private UriInfo uri(MultivaluedMap<String, String> query) {
        UriInfo uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(query);
        return uri;
    }

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void assertInvalid(org.junit.jupiter.api.function.Executable request) {
        assertEquals(CatalogFailure.Reason.INVALID_REQUEST, assertThrows(CatalogFailure.class, request).reason());
    }
}
