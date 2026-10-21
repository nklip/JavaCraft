package dev.nklip.javacraft.shardshop.order.http;

import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.ordering.OrderListingService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class OrderBrowserResourceTest {
    private static final ContractDocument API = ContractDocument.openApi(Path.of("src/main/resources/contracts/openapi.yaml"));
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Instant TIME = Instant.parse("2026-10-21T10:20:30.123456Z");
    private OrderListingService service;
    @TestHTTPResource
    URI base;

    @BeforeEach
    void replaceService() {
        service = mock();
        QuarkusMock.installMockForType(service, OrderListingService.class);
    }

    @Test
    void defaultsToDescendingRegionalPageWithExplicitNullCursor() throws Exception {
        when(service.orders("US", null, null, SortDirection.DESC, 25, null)).thenReturn(new OrderPage("US", List.of(), null));
        JsonObject response = response("region=US", 200, "OrderPage");
        assertEquals(new JsonObject().put("region", "US").put("items", List.of()).putNull("nextAfter"), response);
        verify(service).orders("US", null, null, SortDirection.DESC, 25, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASC", "DESC"})
    void keepsLargeIdsMoneyFullItemListAndMicrosecondsInFilteredPages(String direction) throws Exception {
        var cursor = new OrderCursor(TIME, Long.MAX_VALUE);
        var order = new ListedOrder(Long.MAX_VALUE, 9007199254740993L, "Ada Maria Lovelace", TIME, "CANCELLED", "OUT_OF_STOCK",
                new BigDecimal("99999999999999999.99"), "EUR", List.of(new ListedItem(11, "Saved first product", 3), new ListedItem(21, "Saved second product", 2)));
        when(service.orders("EU", 21L, null, SortDirection.valueOf(direction), 100, cursor))
                .thenReturn(new OrderPage("EU", List.of(order), cursor));
        JsonObject response = response("region=EU&productId=21&sort=" + direction
                + "&limit=100&afterCreatedAt=2026-10-21T10:20:30.123456Z&afterOrderId=9223372036854775807",
                200, "OrderPage");
        JsonObject item = response.getJsonArray("items").getJsonObject(0);
        assertEquals("9223372036854775807", item.getString("orderId"));
        assertEquals("9007199254740993", item.getString("buyerId"));
        assertEquals("Ada Maria Lovelace", item.getString("buyerName"));
        assertEquals("2026-10-21T10:20:30.123456Z", item.getString("createdAt"));
        assertEquals("99999999999999999.99", item.getString("total"));
        assertEquals("OUT_OF_STOCK", item.getString("cancellationReason"));
        assertEquals(List.of("11", "21"), item.getJsonArray("items").stream()
                .map(value -> ((JsonObject) value).getString("productId")).toList());
        assertEquals(List.of("Saved first product", "Saved second product"), item.getJsonArray("items").stream()
                .map(value -> ((JsonObject) value).getString("productName")).toList());
        assertEquals(TIME.toString(), response.getJsonObject("nextAfter").getString("createdAt"));
        verify(service).orders("EU", 21L, null, SortDirection.valueOf(direction), 100, cursor);
    }

    @Test
    void nameFilterTrimsUnicodeEdgesAndKeepsCaseAndLiteralMetacharacters() throws Exception {
        String name = "🚀".repeat(197) + "A%_";
        when(service.orders("EU", null, name, SortDirection.DESC, 25, null))
                .thenReturn(new OrderPage("EU", List.of(), null));

        response("region=EU&productName=" + URLEncoder.encode(" \u00a0" + name + "\u2003", StandardCharsets.UTF_8),
                200, "OrderPage");

        verify(service).orders("EU", null, name, SortDirection.DESC, 25, null);
    }

    @Test
    void rejectsOverlongUnicodeNameBeforeService() throws Exception {
        assertEquals("INVALID_REQUEST", response("region=US&productName="
                + URLEncoder.encode("🚀".repeat(201), StandardCharsets.UTF_8), 400, "Error").getString("code"));
        verifyNoInteractions(service);
    }

    @Test
    void includesNullCancellationReasonAndReportsShardFailuresAsUnavailable() throws Exception {
        when(service.orders("ASIA", null, null, SortDirection.DESC, 25, null)).thenReturn(new OrderPage("ASIA",
                List.of(new ListedOrder(1, 2, "Buyer", TIME, "CONFIRMED", null, new BigDecimal("0.00"), "USD", List.of())), null));
        JsonObject item = response("region=ASIA", 200, "OrderPage").getJsonArray("items").getJsonObject(0);
        assertTrue(item.containsKey("cancellationReason"));
        assertNull(item.getValue("cancellationReason"));
        when(service.orders("ASIA", null, null, SortDirection.DESC, 25, null))
                .thenThrow(new OrderFailure(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE));
        assertEquals("ORDER_STORE_UNAVAILABLE", response("region=ASIA", 503, "Error").getString("code"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "region=", "region=us", "region=AU", "region=US&region=EU", "region=US&unknown=1",
            "region=US&limit=0", "region=US&limit=101", "region=US&limit=01", "region=US&limit=1.0",
            "region=US&limit=25&limit=25", "region=US&sort=desc", "region=US&sort=", "region=US&sort=ASC&sort=DESC",
            "region=US&productId=0", "region=US&productId=01", "region=US&productId=9223372036854775808",
            "region=US&productId=1&productId=2", "region=US&afterOrderId=1", "region=US&afterCreatedAt=2026-10-21T10:20:30Z",
            "region=US&productName=", "region=US&productName=%20%C2%A0%E2%80%83", "region=US&productName=a%00b",
            "region=US&productName=a%0Ab", "region=US&productName=a%09b", "region=US&productName=a%7Fb",
            "region=US&productName=a%C2%85b", "region=US&productName=%ED%A0%80", "region=US&productName=%C3%28",
            "region=US&productName=%F0%9F%9A", "region=US&productName=a&productName=b",
            "region=US&productId=1&productName=product",
            "region=US&afterOrderId=0&afterCreatedAt=2026-10-21T10:20:30Z",
            "region=US&afterOrderId=1&afterCreatedAt=2026-02-30T10:20:30Z",
            "region=US&afterOrderId=1&afterCreatedAt=2026-10-21T10:20:30.1234567Z",
            "region=US&afterOrderId=1&afterCreatedAt=2026-10-21T10:20:30%2B00:00",
            "region=US&afterOrderId=1&afterCreatedAt=0000-10-21T10:20:30Z",
            "region=US&afterOrderId=1&afterCreatedAt=2026-10-21T10:20:30Z&afterOrderId=2"})
    void rejectsMalformedUnknownRepeatedAndUnpairedInputsBeforeService(String query) throws Exception {
        assertEquals("INVALID_REQUEST", response(query, 400, "Error").getString("code"));
        verifyNoInteractions(service);
    }

    private JsonObject response(String query, int status, String schema) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/api/v1/orders?" + query)).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        var json = new JsonObject(response.body());
        assertEquals(List.of(), API.errors("#/components/schemas/" + schema, json));
        return json;
    }
}
