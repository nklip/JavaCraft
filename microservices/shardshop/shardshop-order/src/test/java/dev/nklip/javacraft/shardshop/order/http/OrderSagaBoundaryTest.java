package dev.nklip.javacraft.shardshop.order.http;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(OrderSagaBoundaryTest.SagaProfile.class)
class OrderSagaBoundaryTest {
    @TestHTTPResource
    URI base;

    /** A saga listener cannot accidentally serve client requests or static order pages. */
    @Test
    void onlyManagementEndpointsAreReachable() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            for (String path : new String[]{"/api/v1/buyers/1", "/api/v1/orders?region=US", "/orders/"}) {
                var response = client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, response.statusCode(), path);
            }
            var response = client.send(HttpRequest.newBuilder(base.resolve("/internal/order/write-metrics"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var metrics = client.send(HttpRequest.newBuilder(base.resolve("/q/metrics"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("shardshop_order_db_pool_active"));
            assertTrue(metrics.body().contains("shardshop_order_outbox_pending"));
            assertTrue(metrics.body().contains("shardshop_order_cdc_recovery_required"));
        }
    }

    public static class SagaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("shardshop.order.process", "saga");
        }
    }
}
