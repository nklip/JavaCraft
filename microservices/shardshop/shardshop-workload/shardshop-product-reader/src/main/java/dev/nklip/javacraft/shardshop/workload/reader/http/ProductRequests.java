package dev.nklip.javacraft.shardshop.workload.reader.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.nklip.javacraft.shardshop.common.HttpResponseException;
import dev.nklip.javacraft.shardshop.common.JsonResponses;
import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult.Outcome;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.IdleConnectionEvictor;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends product GETs through an Apache HttpClient 5 connection pool and maps each result to an outcome. The pool
 * keeps at most the configured number of HTTP/1.1 connections. A connection retires at its next lease after its
 * time to live, closes after the idle timeout, and closes after a failure. A scheduled cancel ends each read at the
 * overall deadline, which includes the wait for a connection and all attempts.
 */
public final class ProductRequests implements AutoCloseable {
    /** Product responses are small; a larger body is an invalid response. */
    static final int MAX_BODY_BYTES = 2_097_152;

    private final URI productUrl;
    private final long deadlineMillis;
    private final PoolingHttpClientConnectionManager pool;
    private final CloseableHttpClient http;
    private final IdleConnectionEvictor evictor;
    private final ScheduledThreadPoolExecutor deadlines;

    public ProductRequests(Settings settings) {
        this.productUrl = settings.productUrl();
        this.deadlineMillis = settings.requestDeadline().toMillis();
        Timeout deadline = Timeout.of(settings.requestDeadline());
        this.pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(settings.connections())
                .setMaxConnPerRoute(settings.connections())
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
                .setConnPoolPolicy(PoolReusePolicy.FIFO)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(settings.connectTimeout()))
                        .setSocketTimeout(deadline)
                        .setTimeToLive(TimeValue.of(settings.connectionLifetime()))
                        .build())
                .build();
        this.http = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(deadline)
                        .setResponseTimeout(deadline)
                        .build())
                .setRetryStrategy(new RetryWithoutResponse(settings.maxAttempts() - 1, settings.retryInterval()))
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .build();
        // The evictor checks at least once a second, thus a connection closes soon after its idle timeout.
        Duration check = settings.idleTimeout().compareTo(Duration.ofSeconds(1)) < 0
                ? settings.idleTimeout() : Duration.ofSeconds(1);
        this.evictor = new IdleConnectionEvictor(pool, TimeValue.of(check), TimeValue.of(settings.idleTimeout()));
        this.deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("product-read-deadline").daemon().factory());
        deadlines.setRemoveOnCancelPolicy(true);
        evictor.start();
    }

    public int maxConnections() {
        return pool.getMaxTotal();
    }

    /** Leased connections, reads that wait for a connection, free connections and the maximum, now. */
    public PoolStats poolStats() {
        return pool.getTotalStats();
    }

    public <T> ReadResult<T> get(Read<T> read) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Product read was stopped");
        }
        HttpGet request = new HttpGet(productUrl.resolve(read.path()));
        request.setHeader("Accept", "application/json");
        ScheduledFuture<?> deadline = deadlines.schedule(request::cancel, deadlineMillis, TimeUnit.MILLISECONDS);
        try {
            return http.execute(request, response -> result(response, read));
        } catch (IOException failure) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Product read was stopped");
            }
            // The cancel, the response timeout and the wait for a connection all end at the deadline.
            boolean timedOut = request.isCancelled()
                    || failure instanceof InterruptedIOException && !(failure instanceof ConnectTimeoutException);
            return ReadResult.failed(timedOut ? Outcome.DEADLINE : Outcome.TRANSPORT);
        } finally {
            deadline.cancel(false);
        }
    }

    /** Closes all connections at once; a read in progress fails. */
    @Override
    public void close() {
        evictor.shutdown();
        deadlines.shutdownNow();
        http.close(CloseMode.IMMEDIATE);
    }

    private static <T> ReadResult<T> result(ClassicHttpResponse response, Read<T> read) throws IOException {
        byte[] body = response.getEntity() == null ? new byte[0]
                : EntityUtils.toByteArray(response.getEntity(), MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return ReadResult.failed(Outcome.INVALID_RESPONSE);
        }
        try {
            return ReadResult.ok(JsonResponses.decode(response.getCode(), body, 200, read.decoder()));
        } catch (HttpResponseException rejected) {
            return ReadResult.failed(outcome(rejected));
        } catch (JsonProcessingException | CharacterCodingException | IllegalArgumentException invalid) {
            return ReadResult.failed(Outcome.INVALID_RESPONSE);
        }
    }

    private static Outcome outcome(HttpResponseException rejected) {
        return switch (rejected.statusCode()) {
            case 404 -> Outcome.NOT_FOUND;
            case 503 -> rejected.errorCode().filter("READ_REPLICA_UNAVAILABLE"::equals).isPresent()
                    ? Outcome.REPLICA_UNAVAILABLE : Outcome.UNAVAILABLE;
            default -> Outcome.HTTP_ERROR;
        };
    }

    /**
     * Sends a GET again only after a failure without an HTTP response, such as a refused, reset or closed
     * connection, at a fixed interval. A status is never repeated: product already limits its own database work.
     */
    private static final class RetryWithoutResponse extends DefaultHttpRequestRetryStrategy {
        private final TimeValue interval;

        RetryWithoutResponse(int maxRetries, Duration interval) {
            // Timeouts and cancels are InterruptedIOExceptions: they end at the deadline.
            super(maxRetries, TimeValue.of(interval), List.<Class<? extends IOException>>of(
                    InterruptedIOException.class, UnknownHostException.class, SSLException.class), List.of());
            this.interval = TimeValue.of(interval);
        }

        @Override
        public TimeValue getRetryInterval(HttpRequest request, IOException exception, int execCount,
                                          HttpContext context) {
            return interval;
        }
    }

    /** Pool, timeout and retry settings; the reader configuration supplies the policy defaults. */
    public record Settings(URI productUrl, int connections, Duration connectTimeout, Duration requestDeadline,
                           Duration connectionLifetime, Duration idleTimeout, int maxAttempts,
                           Duration retryInterval) {
        public Settings {
            Objects.requireNonNull(productUrl);
            if (!("http".equals(productUrl.getScheme()) || "https".equals(productUrl.getScheme()))
                    || productUrl.getHost() == null || productUrl.getRawQuery() != null
                    || productUrl.getRawFragment() != null || productUrl.getRawUserInfo() != null
                    || connections < 1 || connections > 64 || !requestDeadline.isPositive()
                    || !connectTimeout.isPositive() || connectTimeout.compareTo(requestDeadline) > 0
                    || !connectionLifetime.isPositive() || !idleTimeout.isPositive()
                    || maxAttempts < 1 || maxAttempts > 5 || retryInterval.isNegative()
                    || retryInterval.compareTo(requestDeadline) >= 0) {
                throw new IllegalArgumentException("Invalid reader HTTP configuration");
            }
        }
    }
}
