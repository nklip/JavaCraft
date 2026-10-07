package dev.nklip.javacraft.shardshop.workload.seeder;

import dev.nklip.javacraft.shardshop.common.HttpResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Repeats one idempotent request after a transport failure or a 503 response, with capped exponential backoff.
 * Other HTTP statuses and invalid responses fail at once.
 */
public final class RetryPolicy {
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Pause pause;

    public RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Pause pause) {
        this.pause = Objects.requireNonNull(pause);
        if (maxAttempts < 1 || maxAttempts > 10 || initialBackoff.isNegative() || initialBackoff.isZero()
                || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Invalid seeder retry configuration");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    public <T> T call(String operation, Request<T> request) throws SeedingException, InterruptedException {
        Duration delay = initialBackoff;
        for (int attempt = 1; ; attempt++) {
            try {
                return request.send();
            } catch (HttpResponseException failure) {
                if (failure.statusCode() != 503 || attempt == maxAttempts) {
                    throw failed(operation, attempt, failure);
                }
            } catch (IOException failure) {
                if (attempt == maxAttempts) {
                    throw failed(operation, attempt, failure);
                }
            } catch (IllegalArgumentException failure) {
                throw failed(operation, attempt, failure);
            }
            pause.sleep(delay);
            delay = delay.multipliedBy(2);
            if (delay.compareTo(maxBackoff) > 0) {
                delay = maxBackoff;
            }
        }
    }

    private static SeedingException failed(String operation, int attempt, Exception failure) {
        String reason;
        if (failure instanceof HttpResponseException response) {
            reason = "HTTP " + response.statusCode() + response.errorCode().map(code -> " " + code).orElse("");
        } else if (failure instanceof IllegalArgumentException) {
            // Local validation messages are fixed text and contain no response values.
            reason = failure.getMessage();
        } else {
            reason = failure.getClass().getSimpleName();
        }
        return new SeedingException(operation + " failed on attempt " + attempt + ": " + reason, failure);
    }

    @FunctionalInterface
    public interface Request<T> {
        T send() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface Pause {
        void sleep(Duration delay) throws InterruptedException;
    }
}
