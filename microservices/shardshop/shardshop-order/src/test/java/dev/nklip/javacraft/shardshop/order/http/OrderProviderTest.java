package dev.nklip.javacraft.shardshop.order.http;

import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Address;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Allocation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.AllocationRequest;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Buyer;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.BuyerCreation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Creation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Order;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.OrderCreation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.OrderItem;
import dev.nklip.javacraft.shardshop.order.ordering.OrderService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises the deployed REST stack and checks provider bodies against its OpenAPI contract. */
@QuarkusTest
class OrderProviderTest {
    private static final ContractDocument API = ContractDocument.openApi(Path.of("src/main/resources/contracts/openapi.yaml"));
    private static final long BUYER_ID = 9007199254740993L;
    private static final long ORDER_ID = Long.MAX_VALUE;
    private static final String BUYERS = "/api/v1/buyers";
    private static final String BUYER = BUYERS + "/" + BUYER_ID;
    private static final String ALLOCATIONS = BUYER + "/order-allocations";
    private static final String ORDER = BUYER + "/orders/" + ORDER_ID;
    private static final BuyerCreation PERSON = new BuyerCreation(" María 🚀 ", "O'Connor", "a+b@example.org", "+12025550123",
            new Address("1 Main St", "Paris", "75001", "FR"), "EU");
    private static final String BUYER_BODY = """
            {"firstName":" María 🚀 ","surname":"O'Connor","email":"a+b@example.org","phone":"+12025550123",
             "address":{"line1":"1 Main St","city":"Paris","postalCode":"75001","countryCode":"FR"},"region":"EU"}
            """;
    private static final String ALLOCATION_BODY = "{\"runName\":\"run 🚀\",\"requestOrdinal\":\"9223372036854775807\"}";
    private static final String ORDER_BODY = "{\"currency\":\"EUR\",\"items\":[{\"sellerId\":\"9007199254740993\","
            + "\"productId\":\"9223372036854775807\",\"quantity\":%s}]}";
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(2)).build();
    private OrderService service;
    @TestHTTPResource
    URI base;

    @BeforeEach
    void replaceService() {
        service = mock(OrderService.class);
        QuarkusMock.installMockForType(service, OrderService.class);
    }

    @Test
    void negotiatesHttpOneUntilHttpTwoSupportIsIntroduced() throws Exception {
        HttpResponse<String> response = send("GET", BUYERS + "/0", null);
        invalid(response);
        assertEquals(HttpClient.Version.HTTP_1_1, response.version());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createsAndRecoversBuyerWithoutChangingUnicodeOrLargeIds(boolean created) throws Exception {
        when(service.createBuyer("Buyer.Key", PERSON)).thenReturn(new Creation<>(new Buyer(BUYER_ID, PERSON), created));
        HttpResponse<String> response = send("POST", BUYERS, BUYER_BODY, "Idempotency-Key", "Buyer.Key");
        JsonObject body = response(response, created ? 201 : 200, "#/components/schemas/Buyer");
        assertEquals(Long.toString(BUYER_ID), body.getString("buyerId"));
        assertEquals(PERSON.firstName(), body.getString("firstName"));
        assertEquals(PERSON.email(), body.getString("email"));
        verify(service).createBuyer("Buyer.Key", PERSON);
        when(service.buyer(BUYER_ID)).thenReturn(new Buyer(BUYER_ID, PERSON));
        assertEquals(body, response(send("GET", BUYER, null), 200, "#/components/schemas/Buyer"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsCommittedOrRecoveredAllocationAsText(boolean created) throws Exception {
        var payload = new AllocationRequest("run 🚀", Long.MAX_VALUE);
        when(service.allocateOrder(BUYER_ID, payload)).thenReturn(new Creation<>(
                new Allocation(BUYER_ID, ORDER_ID, payload.runName(), payload.requestOrdinal()), created));
        JsonObject body = response(send("POST", ALLOCATIONS, ALLOCATION_BODY), created ? 201 : 200,
                "#/components/schemas/Allocation");
        assertEquals(Long.toString(ORDER_ID), body.getString("orderId"));
        assertEquals(Long.toString(Long.MAX_VALUE), body.getString("requestOrdinal"));
        verify(service).allocateOrder(BUYER_ID, payload);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_STOCK", "PENDING_LEDGER", "CONFIRMED", "CANCELLED"})
    void returnsPendingOrTerminalStatusAndCancellationContract(String status) throws Exception {
        String reason = "CANCELLED".equals(status) ? "OUT_OF_STOCK" : null;
        Order order = new Order(BUYER_ID, ORDER_ID, "fingerprint", "EUR", new BigDecimal("39.90"), status, reason);
        when(service.putOrder(anyLong(), anyLong(), any())).thenReturn(order);
        when(service.order(BUYER_ID, ORDER_ID)).thenReturn(order);
        HttpResponse<String> result = send("PUT", ORDER, ORDER_BODY.formatted("2e0"));
        String operation = "#/paths/~1api~1v1~1buyers~1{buyerId}~1orders~1{orderId}/put/responses/";
        JsonObject body = response(result, order.pending() ? 202 : 200,
                operation + (order.pending() ? "202" : "200") + "/content/application~1json/schema");
        assertEquals(ORDER, body.getString("statusUrl"));
        if (order.pending()) {
            assertEquals(ORDER, result.headers().firstValue("Location").orElseThrow());
        }
        assertEquals(reason, body.getString("cancellationReason"));
        assertEquals("CANCELLED".equals(status), body.containsKey("cancellationReason"));
        assertEquals(body, response(send("GET", ORDER, null), 200, "#/components/schemas/OrderStatus"));
        verify(service).putOrder(BUYER_ID, ORDER_ID,
                new OrderCreation("EUR", List.of(new OrderItem(BUYER_ID, ORDER_ID, 2))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "2.0", "2e0", "2.000000000000000000000"})
    void quantitiesNormalizeExactlyBeforeReachingService(String quantity) throws Exception {
        when(service.putOrder(anyLong(), anyLong(), any())).thenThrow(new OrderFailure(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE));
        response(send("PUT", ORDER, ORDER_BODY.formatted(quantity)), 503, "#/components/schemas/Error");
        verify(service).putOrder(BUYER_ID, ORDER_ID,
                new OrderCreation("EUR", List.of(new OrderItem(BUYER_ID, ORDER_ID, 2))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "true", "null", "\"2\"", "2.5", "2.000000000000000001",
            "2147483648", "2147483647.000000001", "1e10000"})
    void rejectsInvalidQuantitiesBeforeIo(String quantity) throws Exception {
        invalid(send("PUT", ORDER, ORDER_BODY.formatted(quantity)));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsUnknownRepeatedAndMalformedInputBeforeIo() throws Exception {
        invalid(send("POST", BUYERS, BUYER_BODY));
        invalid(send("POST", BUYERS, BUYER_BODY, "Idempotency-Key", "Buyer.Key", "Idempotency-Key", "Buyer.Key"));
        invalid(send("POST", BUYERS, BUYER_BODY, "Idempotency-Key", "Bad Key"));
        invalid(send("POST", BUYERS, BUYER_BODY.replace("\"region\":\"EU\"", "\"region\":\"EU\",\"region\":\"EU\""),
                "Idempotency-Key", "Buyer.Key"));
        invalid(send("POST", BUYERS, BUYER_BODY.replace("\"countryCode\":\"FR\"", "\"countryCode\":\"FR\",\"extra\":1"),
                "Idempotency-Key", "Buyer.Key"));
        invalid(send("POST", ALLOCATIONS, ALLOCATION_BODY.replace("\"9223372036854775807\"", "0")));
        invalid(send("POST", ALLOCATIONS, ALLOCATION_BODY + " {}"));
        invalid(send("PUT", ORDER, ORDER_BODY.formatted("2").replace("\"sellerId\":\"9007199254740993\"", "\"sellerId\":9007199254740993")));
        invalid(send("PUT", ORDER, ORDER_BODY.formatted("2").replace("\"EUR\"", "\"EUR\",\"price\":\"1.00\"")));
        invalid(send("PUT", ORDER, "{\"currency\":\"EUR\",\"items\":[{\"sellerId\":\"1\",\"productId\":\"2\",\"quantity\":1},"
                + "{\"sellerId\":\"1\",\"productId\":\"2\",\"quantity\":2}]}"));
        invalid(send("GET", BUYERS + "/01", null));
        invalid(send("GET", BUYER + "/orders/9223372036854775808", null));
        for (String path : List.of(BUYERS, ALLOCATIONS, ORDER)) {
            String method = path.equals(ORDER) ? "PUT" : "POST";
            String payload = path.equals(BUYERS) ? BUYER_BODY : path.equals(ORDER) ? ORDER_BODY.formatted("2") : ALLOCATION_BODY;
            invalid(send(method, path + "?shard=a&shard=b", payload, "Idempotency-Key", "Buyer.Key"));
        }
        invalid(send("GET", BUYER + "?region=EU", null));
        invalid(send("GET", ORDER + "?region=EU&region=EU", null));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidEncodingContentTypesAndOversizedBodiesBeforeIo() throws Exception {
        invalid(sendBytes("POST", BUYERS, new byte[]{(byte) 0xc3, 0x28}, "Content-Type", "application/json", "Idempotency-Key", "Buyer.Key"));
        invalid(sendBytes("POST", BUYERS, BUYER_BODY.getBytes(StandardCharsets.UTF_8), "Content-Type", "text/plain", "Idempotency-Key", "Buyer.Key"));
        invalid(sendBytes("PUT", ORDER, ORDER_BODY.formatted("1").getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json", "Content-Type", "application/json"));
        invalid(send("PUT", ORDER, ""));
        invalid(send("POST", ALLOCATIONS, " ".repeat(262145)));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"INVALID_REQUEST,400", "BUYER_IDEMPOTENCY_CONFLICT,409", "ORDER_ID_CONFLICT,409",
            "ORDER_RECONCILIATION_REQUIRED,409", "PRODUCT_NOT_FOUND,422", "MIXED_CURRENCIES,422", "CURRENCY_MISMATCH,422", "PRODUCT_CURRENCY_POLICY,422",
            "ORDER_STORE_UNAVAILABLE,503", "CATALOG_UNAVAILABLE,503", "ID_GENERATION_UNAVAILABLE,503"})
    void mapsTypedFailuresToStableCodes(String code, int status) throws Exception {
        when(service.putOrder(anyLong(), anyLong(), any())).thenThrow(new OrderFailure(OrderFailure.Reason.valueOf(code)));
        assertEquals(code, response(send("PUT", ORDER, ORDER_BODY.formatted("1")), status,
                "#/components/schemas/Error").getString("code"));
    }

    @Test
    void distinguishesMissingBuyerAllocationAndOrderReads() throws Exception {
        when(service.buyer(BUYER_ID)).thenThrow(new OrderFailure(OrderFailure.Reason.BUYER_NOT_FOUND));
        when(service.allocateOrder(anyLong(), any())).thenThrow(new OrderFailure(OrderFailure.Reason.MISSING_BUYER));
        when(service.order(BUYER_ID, ORDER_ID)).thenThrow(new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND));
        assertEquals("BUYER_NOT_FOUND", response(send("GET", BUYER, null), 404, "#/components/schemas/Error").getString("code"));
        assertEquals("BUYER_NOT_FOUND", response(send("POST", ALLOCATIONS, ALLOCATION_BODY), 422, "#/components/schemas/Error").getString("code"));
        assertEquals("ORDER_NOT_FOUND", response(send("GET", ORDER, null), 404, "#/components/schemas/Error").getString("code"));
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        String[] all = new String[headers.length + (body == null ? 0 : 2)];
        if (body != null) {
            all[0] = "Content-Type";
            all[1] = "application/json";
        }
        System.arraycopy(headers, 0, all, all.length - headers.length, headers.length);
        return sendBytes(method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), all);
    }

    private HttpResponse<String> sendBytes(String method, String path, byte[] body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(8))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length != 0) {
            builder.headers(headers);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonObject response(HttpResponse<String> response, int expectedStatus, String schema) {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        Object body = ContractDocument.json(response.body());
        assertEquals(List.of(), API.errors(schema, body), response.body());
        return (JsonObject) body;
    }

    private void invalid(HttpResponse<String> response) {
        assertEquals("INVALID_REQUEST", response(response, 400, "#/components/schemas/Error").getString("code"));
    }
}
