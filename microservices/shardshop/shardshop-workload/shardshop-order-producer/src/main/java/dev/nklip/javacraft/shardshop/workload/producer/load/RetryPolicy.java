package dev.nklip.javacraft.shardshop.workload.producer.load;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.nklip.javacraft.shardshop.common.HttpResponseException;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/** Retries only transport failures and 503s; the enclosing phase bounds the complete operation. */
public final class RetryPolicy {
    private final int attempts;
    private final Duration initial;
    private final Duration maximum;
    private final Pause pause;
    private final DoubleSupplier random;

    public RetryPolicy(int attempts, Duration initial, Duration maximum, Pause pause, DoubleSupplier random) {
        if (attempts < 1 || attempts > 10 || initial.isZero() || initial.isNegative()
                || maximum.compareTo(initial) < 0 || maximum.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid producer retry configuration");
        }
        this.attempts = attempts;
        this.initial = initial;
        this.maximum = maximum;
        this.pause = Objects.requireNonNull(pause);
        this.random = Objects.requireNonNull(random);
    }

    public <T> T call(String operation, Request<T> request) throws ProducerException, InterruptedException {
        Duration backoff = initial;
        for (int attempt = 1; ; attempt++) {
            try {
                return request.send();
            } catch (HttpResponseException failure) {
                if (failure.statusCode() != 503 || attempt == attempts) {
                    throw failed(operation, failure);
                }
            } catch (JsonProcessingException | CharacterCodingException failure) {
                throw failed(operation, failure);
            } catch (IOException failure) {
                if (attempt == attempts) {
                    throw failed(operation, failure);
                }
            } catch (IllegalArgumentException failure) {
                throw failed(operation, failure);
            }
            pause.sleep(jitter(backoff));
            backoff = backoff.multipliedBy(2).compareTo(maximum) > 0 ? maximum : backoff.multipliedBy(2);
        }
    }

    private Duration jitter(Duration backoff) {
        return Duration.ofNanos((long) (backoff.toNanos() * (0.5 + random.getAsDouble() * 0.5)));
    }

    static ProducerException failed(String operation, Exception cause) {
        String reason = cause instanceof HttpResponseException response ? "HTTP " + response.statusCode()
                + response.errorCode().map(code -> " " + code).orElse("") : cause.getClass().getSimpleName();
        return new ProducerException(operation + " failed: " + reason, cause);
    }

    @FunctionalInterface
    public interface Request<T> {
        T send() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }
}
