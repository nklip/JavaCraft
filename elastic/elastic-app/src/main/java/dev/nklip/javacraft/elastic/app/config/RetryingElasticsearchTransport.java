package dev.nklip.javacraft.elastic.app.config;

import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.Endpoint;
import co.elastic.clients.transport.TransportOptions;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.elastic.api.validation.PositiveNumber;

@Slf4j
public class RetryingElasticsearchTransport implements ElasticsearchTransport {
    private final ElasticsearchTransport delegate;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    RetryingElasticsearchTransport(
            ElasticsearchTransport delegate,
            int maxAttempts,
            long initialBackoffMs,
            long maxBackoffMs
    ) {
        this.delegate = delegate;
        this.maxAttempts = PositiveNumber.positiveOrDefault(maxAttempts, 1);
        this.initialBackoffMs = PositiveNumber.positiveOrDefault(initialBackoffMs, 1);
        this.maxBackoffMs = Math.max(
                this.initialBackoffMs,
                PositiveNumber.positiveOrDefault(maxBackoffMs, this.initialBackoffMs)
        );
    }

    @Override
    public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request,
            Endpoint<RequestT, ResponseT, ErrorT> endpoint,
            TransportOptions options
    ) throws IOException {
        IOException latestException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return delegate.performRequest(request, endpoint, options);
            } catch (IOException ioe) {
                latestException = ioe;
                if (attempt >= maxAttempts) {
                    throw ioe;
                }

                long backoffMs = backoffDelayMs(attempt, initialBackoffMs, maxBackoffMs);
                log.warn(
                        "Elasticsearch request failed with IO error (attempt {}/{}). Retrying in {} ms.",
                        attempt,
                        maxAttempts,
                        backoffMs,
                        ioe
                );
                sleep(backoffMs);
            }
        }

        throw latestException == null ? new IOException("Elasticsearch request failed without root cause.")
                : latestException;
    }

    @Override
    public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
            RequestT request,
            Endpoint<RequestT, ResponseT, ErrorT> endpoint,
            TransportOptions options
    ) {
        // Current services use synchronous operations only.
        // Keep async behavior delegated as-is to avoid changing thread/queue semantics.
        return delegate.performRequestAsync(request, endpoint, options);
    }

    @Override
    public JsonpMapper jsonpMapper() {
        return delegate.jsonpMapper();
    }

    @Override
    public TransportOptions options() {
        return delegate.options();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    static long backoffDelayMs(int attempt, long initialBackoffMs, long maxBackoffMs) {
        long initial = PositiveNumber.positiveOrDefault(initialBackoffMs, 1L);
        long max = Math.max(initial, PositiveNumber.positiveOrDefault(maxBackoffMs, initial));
        int exponent = Math.max(0, attempt - 1);

        long delay = initial;
        for (int i = 0; i < exponent; i++) {
            if (delay >= max / 2) {
                return max;
            }
            delay *= 2;
        }
        return Math.min(delay, max);
    }

    static void sleep(long delayMs) throws IOException {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.max(0, delayMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during Elasticsearch retry backoff.", ie);
        }
    }

}
