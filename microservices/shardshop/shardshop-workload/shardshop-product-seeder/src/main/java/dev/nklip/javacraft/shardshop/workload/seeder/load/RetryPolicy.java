package dev.nklip.javacraft.shardshop.workload.seeder.load;

import dev.nklip.javacraft.shardshop.common.HttpResponseException;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Repeats one idempotent request after a transport failure or a 503 response, with capped exponential backoff.
 * Verification reads also retry 404 responses, because a replica can lag an acknowledged creation.
 * Other HTTP statuses and invalid responses fail at once. Each attempt waits for its start in the live paced schedule
 * and counts as one outgoing request of its traffic category.
 */
public final class RetryPolicy {
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Pause pause;
    private final WorkloadControl control;

    public RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Pause pause,
                       WorkloadControl control) {
        this.pause = Objects.requireNonNull(pause);
        this.control = Objects.requireNonNull(control);
        if (maxAttempts < 1 || maxAttempts > 10 || initialBackoff.isNegative() || initialBackoff.isZero()
                || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Invalid seeder retry configuration");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    public <T> T call(String category, String operation, Request<T> request)
            throws SeedingException, InterruptedException {
        Duration delay = initialBackoff;
        for (int attempt = 1; ; attempt++) {
            try {
                return send(category, attempt > 1, request);
            } catch (HttpResponseException failure) {
                boolean retryable = failure.statusCode() == 503
                        || failure.statusCode() == 404 && CatalogSeeder.VERIFICATION.equals(category);
                if (!retryable || attempt == maxAttempts) {
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

    private <T> T send(String category, boolean retry, Request<T> request) throws IOException, InterruptedException {
        control.awaitStart();
        control.started();
        control.traffic().sent(category, retry);
        try (WorkloadControl.Operation ignored = control.begin()) {
            return request.send();
        } catch (IOException | IllegalArgumentException failure) {
            control.traffic().failed(category);
            throw failure;
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
