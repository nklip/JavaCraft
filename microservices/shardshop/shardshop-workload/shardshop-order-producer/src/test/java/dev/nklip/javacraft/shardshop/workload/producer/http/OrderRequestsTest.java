package dev.nklip.javacraft.shardshop.workload.producer.http;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.producer.plan.PlannedOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class OrderRequestsTest {
    private static final String BUYER = "9007199254740993";
    private static final String ORDER = "9223372036854775807";
    private static final String PATH = "/api/v1/buyers/" + BUYER + "/orders/" + ORDER;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = mock(HttpClient.class);
    private final OrderRequests requests = new OrderRequests(new JsonHttpClient(http,
            URI.create("http://order:8080"), Duration.ofSeconds(5)));

    @Test
    void allocationUsesBodyCoordinatesAndRetainsFullPrecisionIds() throws Exception {
        reply(201, "{\"buyerId\":\"" + BUYER + "\",\"orderId\":\"" + ORDER
                + "\",\"runName\":\"run-1\",\"requestOrdinal\":\"9223372036854775807\"}");
        assertEquals(ORDER, requests.allocate(BUYER, "run-1", Long.MAX_VALUE));
        HttpRequest request = captured();
        assertEquals("POST", request.method());
        assertEquals(URI.create("http://order:8080/api/v1/buyers/" + BUYER + "/order-allocations"), request.uri());
        assertFalse(request.headers().firstValue("Idempotency-Key").isPresent());
        assertEquals(json.readTree("{\"runName\":\"run-1\",\"requestOrdinal\":\"9223372036854775807\"}"),
                json.readTree(body(request)));
    }

    @Test
    void sendsOnlyCurrencyAndItemsAndDecodesPendingThenTerminalStatus() throws Exception {
        PlannedOrder planned = new PlannedOrder(BUYER, ORDER, "EUR", List.of(
                new PlannedOrder.Item("9007199254740995", "9007199254740997", 2)));
        reply(202, status("PENDING_STOCK", null));
        OrderStatus pending = requests.submit(planned);
        assertEquals("PENDING_STOCK", pending.status());
        assertFalse(pending.terminal());
        HttpRequest put = captured();
        assertEquals("PUT", put.method());
        assertEquals(URI.create("http://order:8080" + PATH), put.uri());
        assertEquals(json.readTree("{\"currency\":\"EUR\",\"items\":[{\"sellerId\":\"9007199254740995\","
                + "\"productId\":\"9007199254740997\",\"quantity\":2}]}"), json.readTree(body(put)));
        reply(200, status("CANCELLED", "UNSUPPORTED_CURRENCY"));
        OrderStatus terminal = requests.status(BUYER, ORDER);
        assertEquals("UNSUPPORTED_CURRENCY", terminal.cancellationReason());
        assertEquals(true, terminal.terminal());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CNY", "EUR", "GBP", "JPY", "KRW", "USD", "VND"})
    void submitsEverySupportedCurrencyThroughTheHttpBoundary(String currency) throws Exception {
        PlannedOrder planned = new PlannedOrder(BUYER, ORDER, currency, List.of(
                new PlannedOrder.Item("9007199254740995", "9007199254740997", 2)));
        reply(202, status("PENDING_STOCK", null));

        assertEquals("PENDING_STOCK", requests.submit(planned).status());

        HttpRequest put = captured();
        assertEquals("PUT", put.method());
        assertEquals(currency, json.readTree(body(put)).path("currency").textValue());
    }

    @Test
    void submitsTheCurrencySelectedForTheRetainedOrdinalThatFailedInTheLab() throws Exception {
        String buyer = "105960420535762945";
        String order = "106367475197460480";
        String path = "/api/v1/buyers/" + buyer + "/orders/" + order;
        // The retained lab ordinal used CNY under the earlier seven-way choice; the HTTP guard must accept it.
        String currency = "CNY";
        reply(202, "{\"buyerId\":\"" + buyer + "\",\"orderId\":\"" + order
                + "\",\"status\":\"PENDING_STOCK\",\"statusUrl\":\"" + path + "\"}");
        var planned = new PlannedOrder(buyer, order, currency, List.of(
                new PlannedOrder.Item("9007199254740995", "9007199254740997", 1)));

        assertEquals(order, requests.submit(planned).orderId());

        HttpRequest put = captured();
        assertEquals(URI.create("http://order:8080" + path), put.uri());
        assertEquals("CNY", json.readTree(body(put)).path("currency").textValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUD", "usd", "JPY ", ""})
    void rejectsCurrenciesOutsideTheSupportedPolicyBeforeHttp(String currency) {
        var planned = new PlannedOrder(BUYER, ORDER, currency, List.of(
                new PlannedOrder.Item("9007199254740995", "9007199254740997", 1)));
        assertThrows(IllegalArgumentException.class, () -> requests.submit(planned));
        verifyNoInteractions(http);
    }

    @Test
    void rejectsMismatchedAllocationAndInvalidInputBeforeIo() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> requests.allocate("01", "run", 0));
        assertThrows(IllegalArgumentException.class, () -> requests.allocate(BUYER, " ", 0));
        assertThrows(IllegalArgumentException.class, () -> requests.allocate(BUYER, "run", -1));
        assertThrows(IllegalArgumentException.class, () -> requests.status(BUYER, "1.0"));
        verifyNoInteractions(http);
        reply(200, "{\"buyerId\":\"" + BUYER + "\",\"orderId\":\"" + ORDER
                + "\",\"runName\":\"other\",\"requestOrdinal\":\"0\"}");
        assertThrows(IllegalArgumentException.class, () -> requests.allocate(BUYER, "run", 0));
    }

    @Test
    void rejectsInvalidStatusShapeCorrelationAndReason() {
        for (String body : List.of(
                status("UNKNOWN", null),
                status("CANCELLED", null),
                status("CONFIRMED", "OUT_OF_STOCK"),
                status("CANCELLED", "OTHER"),
                status("PENDING_LEDGER", null).replace(ORDER, "1"),
                status("CONFIRMED", null).replace(PATH, "/elsewhere"),
                status("CONFIRMED", null).replace("}", ",\"extra\":1}"))) {
            reply(200, body);
            assertThrows(IllegalArgumentException.class, () -> requests.status(BUYER, ORDER));
        }
    }

    @Test
    void rejectsPutBodiesWhoseStateConflictsWithHttpStatus() {
        PlannedOrder planned = new PlannedOrder(BUYER, ORDER, "USD", List.of(
                new PlannedOrder.Item("9007199254740995", "9007199254740997", 1)));
        reply(202, status("CONFIRMED", null));
        assertThrows(IllegalArgumentException.class, () -> requests.submit(planned));
        reply(200, status("PENDING_LEDGER", null));
        assertThrows(IllegalArgumentException.class, () -> requests.submit(planned));
    }

    @Test
    void reportsReplayedPendingOrdersAndRejectsMissingOrInvalidReplayMetadata() throws Exception {
        var planned = new PlannedOrder(BUYER, ORDER, "USD", List.of(
                new PlannedOrder.Item("9007199254740995", "9007199254740997", 1)));
        reply(202, status("PENDING_LEDGER", null), "true");
        assertEquals(new OrderRequests.Submission(new OrderStatus(BUYER, ORDER, "PENDING_LEDGER", PATH, null), true),
                requests.submitWithReplay(planned));
        reply(202, status("PENDING_STOCK", null), "false");
        assertFalse(requests.submitWithReplay(planned).replayed());
        reply(202, status("PENDING_STOCK", null), null);
        assertThrows(java.io.IOException.class, () -> requests.submitWithReplay(planned));
        for (String invalid : new String[]{"TRUE", "yes", ""}) {
            reply(202, status("PENDING_STOCK", null), invalid);
            assertThrows(IllegalArgumentException.class, () -> requests.submitWithReplay(planned));
        }
        reply(200, status("CONFIRMED", null), "false");
        assertThrows(IllegalArgumentException.class, () -> requests.submitWithReplay(planned));
    }

    private static String status(String state, String reason) {
        return "{\"buyerId\":\"" + BUYER + "\",\"orderId\":\"" + ORDER + "\",\"status\":\""
                + state + "\",\"statusUrl\":\"" + PATH + "\""
                + (reason == null ? "" : ",\"cancellationReason\":\"" + reason + "\"") + "}";
    }

    private void reply(int status, String body) {
        reply(status, body, status == 200 ? "true" : "false");
    }

    private void reply(int status, String body, String replay) {
        HttpResponse<byte[]> response = mock();
        when(response.headers()).thenReturn(HttpHeaders.of(replay == null ? Map.of()
                : Map.of("Idempotent-Replay", List.of(replay)), (name, value) -> true));
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private HttpRequest captured() {
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any());
        return request.getValue();
    }

    private static String body(HttpRequest request) {
        Flow.Subscriber<ByteBuffer> subscriber = mock();
        doAnswer(invocation -> {
            Flow.Subscription subscription = invocation.getArgument(0);
            subscription.request(Long.MAX_VALUE);
            return null;
        }).when(subscriber).onSubscribe(any());
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        ArgumentCaptor<ByteBuffer> bytes = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(subscriber).onNext(bytes.capture());
        return StandardCharsets.UTF_8.decode(bytes.getValue()).toString();
    }
}
