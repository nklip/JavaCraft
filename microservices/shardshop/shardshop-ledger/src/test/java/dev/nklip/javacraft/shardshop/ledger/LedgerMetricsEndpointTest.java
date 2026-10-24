package dev.nklip.javacraft.shardshop.ledger;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class LedgerMetricsEndpointTest {
    @TestHTTPResource("/q/metrics")
    URI metrics;

    @Test
    void prometheusEndpointExposesLedgerMetricsWithoutDatabaseOnDisabledStartup() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(metrics).timeout(Duration.ofSeconds(5)).GET().build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("shardshop_ledger_snapshot_success"));
            assertTrue(response.body().contains("shardshop_ledger_pool_max"));
        }
    }
}
