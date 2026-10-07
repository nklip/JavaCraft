package dev.nklip.javacraft.shardshop.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JsonHttpClientTest {
    private final HttpClient http = mock(HttpClient.class);
    private final JsonHttpClient client = new JsonHttpClient(http, URI.create("http://product:8080"), Duration.ofSeconds(5));
    private final JsonNode payload = JsonNodeFactory.instance.objectNode().put("name", "unchanged");

    @ParameterizedTest
    @ValueSource(ints = {200, 201})
    void createsWithAStableKeyAndDecodesTheIssuedId(int status) throws Exception {
        reply(status, "{\"id\":\"9007199254740993\"}");
        assertEquals("9007199254740993", client.post("/api/v1/sellers", "catalog-v1.seller.1", payload,
                node -> JsonFields.id(node, "id")));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any());
        assertEquals(URI.create("http://product:8080/api/v1/sellers"), request.getValue().uri());
        assertEquals("POST", request.getValue().method());
        assertEquals(Duration.ofSeconds(5), request.getValue().timeout().orElseThrow());
        assertEquals("application/json", request.getValue().headers().firstValue("Accept").orElseThrow());
        assertEquals("application/json", request.getValue().headers().firstValue("Content-Type").orElseThrow());
        assertEquals("catalog-v1.seller.1", request.getValue().headers().firstValue("Idempotency-Key").orElseThrow());
        assertEquals(20, request.getValue().bodyPublisher().orElseThrow().contentLength());
    }

    @Test
    void readsAServiceIssuedIdAndRequiresTheReadStatus() throws Exception {
        reply(200, "{\"id\":\"9223372036854775807\"}");
        assertEquals("9223372036854775807", client.get("/lookup", node -> JsonFields.id(node, "id")));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any());
        assertEquals("GET", request.getValue().method());
        assertEquals(URI.create("http://product:8080/lookup"), request.getValue().uri());
        assertFalse(request.getValue().bodyPublisher().isPresent());
        reply(201, "{}");
        assertThrows(IOException.class, () -> client.get("/lookup", JsonNode::asText));
        assertThrows(NullPointerException.class, () -> client.get("/lookup", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"id\":\"1\",\"id\":\"1\"}", "{\"id\":\"1\"} {}"})
    void rejectsInvalidDuplicateAndTrailingJson(String body) {
        reply(201, body);
        assertThrows(IOException.class, () -> client.post("/path", "key", payload, JsonNode::asText));
    }

    @ParameterizedTest
    @ValueSource(ints = {202, 204, 400, 409, 503})
    void rejectsUnexpectedHttpStatus(int status) {
        HttpResponse<byte[]> response = mock();
        when(response.statusCode()).thenReturn(status);
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        assertThrows(IOException.class, () -> client.post("/path", "key", payload, JsonNode::asText));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 503})
    void preservesRejectedStatusWithoutExposingTheServerMessage(int status) {
        reply(status, "{\"code\":\"INVALID_REQUEST\",\"message\":\"private contact details\"}");
        Function<JsonNode, String> decoder = mock();
        HttpResponseException failure = assertThrows(HttpResponseException.class,
                () -> client.post("/path", "key", payload, decoder));
        assertEquals("HTTP response rejected (status " + status + ")", failure.getMessage());
        assertEquals(status, failure.statusCode());
        assertEquals(Optional.of("INVALID_REQUEST"), failure.errorCode());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        verifyNoInteractions(decoder);
        verify(http).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void exposesNotSeededLookupStatusWithoutRetrying() {
        reply(404, "{\"code\":\"SELLER_NOT_FOUND\",\"message\":\"not seeded\"}");
        HttpResponseException failure = assertThrows(HttpResponseException.class,
                () -> client.get("/lookup", JsonNode::asText));
        assertEquals(404, failure.statusCode());
        assertEquals(Optional.of("SELLER_NOT_FOUND"), failure.errorCode());
        verify(http).sendAsync(any(HttpRequest.class), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "<html>private contact details</html>", "{", "null", "[]", "42",
            "\"INVALID_REQUEST\"", "{}", "{\"message\":\"private contact details\"}", "{\"code\":null}",
            "{\"code\":42}", "{\"code\":[]}", "{\"code\":{}}", "{\"code\":\"A\",\"code\":\"B\"}",
            "{\"code\":\"A\"} {}", "{\"code\":\"A\",\"message\":\"one\",\"message\":\"two\"}"})
    void preservesStatusWhenTheErrorBodyCannotSupplyATrustworthyCode(String body) {
        reply(503, body);
        HttpResponseException failure = assertThrows(HttpResponseException.class,
                () -> client.post("/path", "key", payload, JsonNode::asText));
        assertEquals(503, failure.statusCode());
        assertEquals(Optional.empty(), failure.errorCode());
        assertEquals("HTTP response rejected (status 503)", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid_request", "_INVALID", "1_INVALID", " INVALID_REQUEST",
            "INVALID_REQUEST ", "INVALID\nREQUEST", "INVALID\rREQUEST", "INVALID\tREQUEST",
            "INVALID.REQUEST", "private@example.com", "ÉRROR", "INVALID\u0000REQUEST"})
    void rejectsInjectedOrNonCanonicalErrorCodeTokens(String code) {
        reply(400, JsonNodeFactory.instance.objectNode().put("code", code).toString());
        HttpResponseException failure = assertThrows(HttpResponseException.class,
                () -> client.get("/lookup", JsonNode::asText));
        assertEquals(400, failure.statusCode());
        assertEquals(Optional.empty(), failure.errorCode());
        assertEquals("HTTP response rejected (status 400)", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void boundsCodeLengthAndAllowsFutureCanonicalCodes() {
        for (String code : List.of("A", "FUTURE_CODE_2", "A".repeat(64))) {
            reply(409, JsonNodeFactory.instance.objectNode().put("code", code).toString());
            HttpResponseException failure = assertThrows(HttpResponseException.class,
                    () -> client.get("/lookup", JsonNode::asText));
            assertEquals(Optional.of(code), failure.errorCode());
        }
        reply(409, JsonNodeFactory.instance.objectNode().put("code", "A".repeat(65)).toString());
        assertEquals(Optional.empty(), assertThrows(HttpResponseException.class,
                () -> client.get("/lookup", JsonNode::asText)).errorCode());
    }

    @Test
    void preservesRejectedStatusForMalformedUtf8AndAbsentBodies() {
        HttpResponse<byte[]> response = mock();
        when(response.statusCode()).thenReturn(503);
        when(response.body()).thenReturn(new byte[]{(byte) 0xC3, (byte) 0x28}).thenReturn(null);
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponseException failure = assertThrows(HttpResponseException.class,
                    () -> client.get("/lookup", JsonNode::asText));
            assertEquals(503, failure.statusCode());
            assertEquals(Optional.empty(), failure.errorCode());
            assertNull(failure.getCause());
        }
    }

    @Test
    void limitsReceivedBytesBeforeAccumulatingAnOversizedBody() throws Exception {
        reply(201, "{}");
        assertEquals("{}", client.post("/path", "key", payload, JsonNode::toString));
        ArgumentCaptor<HttpResponse.BodyHandler<byte[]>> handler = ArgumentCaptor.captor();
        verify(http).sendAsync(any(HttpRequest.class), handler.capture());
        HttpResponse.ResponseInfo info = mock();
        HttpResponse.BodySubscriber<byte[]> subscriber = handler.getValue().apply(info);
        Flow.Subscription subscription = mock();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[2_097_153])));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> subscriber.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
        verify(subscription).cancel();
    }

    @Test
    void cancelsTheCompleteResponseFutureOnDeadlineOrInterruption() throws Exception {
        CompletableFuture<HttpResponse<byte[]>> pending = mock();
        InterruptedException interrupted = new InterruptedException("cancelled");
        when(pending.get(Duration.ofSeconds(5).toNanos(), TimeUnit.NANOSECONDS))
                .thenThrow(new TimeoutException("body stalled")).thenThrow(interrupted);
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(pending);
        assertEquals("HTTP response deadline exceeded", assertThrows(HttpTimeoutException.class,
                () -> client.post("/path", "key", payload, JsonNode::asText)).getMessage());
        assertSame(interrupted, assertThrows(InterruptedException.class,
                () -> client.post("/path", "key", payload, JsonNode::asText)));
        verify(pending, times(2)).cancel(true);
    }

    @Test
    void propagatesIoAndRuntimeFailuresAndWrapsUnexpectedCheckedFailures() {
        IOException io = new IOException("connection failed");
        IllegalStateException runtime = new IllegalStateException("client failed");
        Exception checked = new Exception("unexpected");
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.failedFuture(io))
                .thenReturn(CompletableFuture.failedFuture(runtime))
                .thenReturn(CompletableFuture.failedFuture(checked));
        assertSame(io, assertThrows(IOException.class, () -> client.post("/path", "key", payload, JsonNode::asText)));
        assertSame(runtime, assertThrows(IllegalStateException.class,
                () -> client.post("/path", "key", payload, JsonNode::asText)));
        assertSame(checked, assertThrows(IOException.class,
                () -> client.post("/path", "key", payload, JsonNode::asText)).getCause());
    }

    @Test
    void rejectsMalformedWireUtf8WithoutReplacementCharacters() {
        HttpResponse<byte[]> response = mock();
        when(response.statusCode()).thenReturn(201);
        when(response.body()).thenReturn(new byte[]{(byte) 0xC3, (byte) 0x28});
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        assertThrows(CharacterCodingException.class, () -> client.post("/path", "key", payload, JsonNode::asText));
    }

    @Test
    void propagatesDecoderFailuresAndRejectsNullResults() {
        reply(201, "{}");
        assertThrows(IllegalArgumentException.class,
                () -> client.post("/path", "key", payload, node -> JsonFields.id(node, "id")));
        assertThrows(NullPointerException.class, () -> client.post("/path", "key", payload, node -> null));
    }

    @Test
    void validatesCreationArgumentsBeforeIo() {
        for (String key : new String[]{null, "", "a\n", "a".repeat(129), "../escape"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.post("/path", key, payload, JsonNode::asText));
        }
        assertThrows(NullPointerException.class, () -> client.post("/path", "key", null, JsonNode::asText));
        assertThrows(NullPointerException.class, () -> client.post("/path", "key", payload, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/data", "http:/missing-host", "http://product?query", "http://product#fragment",
            "http://user@product"})
    void rejectsInvalidServiceUris(String uri) {
        assertThrows(IllegalArgumentException.class, () -> new JsonHttpClient(http, URI.create(uri), Duration.ofSeconds(5)));
    }

    @Test
    void validatesTimeoutAndKeepsRequestsOnConfiguredOrigin() {
        assertThrows(IllegalArgumentException.class, () -> new JsonHttpClient(http, URI.create("http://product"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new JsonHttpClient(http, URI.create("http://product"), Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> client.get("https://product:8080/path"));
        assertThrows(IllegalArgumentException.class, () -> client.get("//elsewhere/path"));
        JsonHttpClient secure = new JsonHttpClient(http, URI.create("https://product"), Duration.ofSeconds(1));
        assertEquals(URI.create("https://product/path"), secure.get("/path").uri());
        HttpRequest get = client.get("/api/v1/example");
        assertEquals("GET", get.method());
        assertFalse(get.bodyPublisher().isPresent());
    }

    private void reply(int status, String body) {
        HttpResponse<byte[]> response = mock();
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
}
