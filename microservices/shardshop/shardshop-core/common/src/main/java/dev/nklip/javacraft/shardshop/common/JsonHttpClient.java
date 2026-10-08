package dev.nklip.javacraft.shardshop.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Bounded JSON HTTP transport with caller-owned request payloads and response decoding. */
public final class JsonHttpClient {
    private final HttpClient http;
    private final URI baseUri;
    private final Duration timeout;
    private final long timeoutNanos;

    public JsonHttpClient(HttpClient http, URI baseUri, Duration timeout) {
        this.http = Objects.requireNonNull(http);
        this.baseUri = Objects.requireNonNull(baseUri);
        this.timeout = Objects.requireNonNull(timeout);
        if (!("http".equals(baseUri.getScheme()) || "https".equals(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getRawQuery() != null
                || baseUri.getRawFragment() != null || baseUri.getRawUserInfo() != null
                || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Invalid HTTP client configuration");
        }
        this.timeoutNanos = timeout.toNanos();
    }

    public <T> T post(String path, String idempotencyKey, JsonNode creationPayload,
                      Function<JsonNode, T> decoder) throws IOException, InterruptedException {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        Objects.requireNonNull(creationPayload);
        Objects.requireNonNull(decoder);
        HttpRequest request = request(path).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(creationPayload.toString())).build();
        return decode(receive(request), 201, decoder);
    }

    public <T> T get(String path, Function<JsonNode, T> decoder) throws IOException, InterruptedException {
        Objects.requireNonNull(decoder);
        return decode(receive(get(path)), 200, decoder);
    }

    private static <T> T decode(HttpResponse<byte[]> response, int acceptedStatus, Function<JsonNode, T> decoder)
            throws IOException {
        return JsonResponses.decode(response.statusCode(), response.body(), acceptedStatus, decoder);
    }

    private HttpResponse<byte[]> receive(HttpRequest request) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 2_097_152));
        try {
            return pending.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new HttpTimeoutException("HTTP response deadline exceeded");
        } catch (InterruptedException failure) {
            pending.cancel(true);
            throw failure;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) {
                throw io;
            }
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("HTTP request failed", failure.getCause());
        }
    }

    public HttpRequest get(String path) {
        return request(path).GET().build();
    }

    private HttpRequest.Builder request(String path) {
        URI target = baseUri.resolve(path);
        if (!Objects.equals(baseUri.getScheme(), target.getScheme())
                || !Objects.equals(baseUri.getRawAuthority(), target.getRawAuthority())) {
            throw new IllegalArgumentException("HTTP request must remain on the configured service");
        }
        return HttpRequest.newBuilder(target).timeout(timeout).header("Accept", "application/json");
    }
}
