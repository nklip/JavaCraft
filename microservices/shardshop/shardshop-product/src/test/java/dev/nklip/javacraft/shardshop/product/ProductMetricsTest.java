package dev.nklip.javacraft.shardshop.product;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The step 3.9 traffic check reads these per-pod counters through the Kubernetes API server pod proxy. */
@QuarkusTest
class ProductMetricsTest {

    @TestHTTPResource("/")
    URI root;

    @Test
    void countsProductReadsByRouteTemplateOnTheApplicationPort() throws Exception {
        double before = productReads(metrics());

        // Invalid IDs return 400 before IO, thus the test needs no database.
        assertEquals(400, get("api/v1/sellers/0/products/1").statusCode());
        assertEquals(400, get("api/v1/sellers/0/products/2").statusCode());

        String after = metrics();
        assertEquals(before + 2, productReads(after));
        assertFalse(after.contains("uri=\"/api/v1/sellers/0/products/1\""), "Raw paths must not become metric series");
    }

    private String metrics() throws IOException, InterruptedException {
        HttpResponse<String> response = get("q/metrics");
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static double productReads(String metrics) {
        return metrics.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_count{"))
                .filter(line -> line.contains("method=\"GET\"") && line.contains("status=\"400\"")
                        && line.contains("uri=\"/api/v1/sellers/{sellerId}/products/{productId}\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }
}
