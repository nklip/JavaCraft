package dev.nklip.javacraft.shardshop.workload.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The packaged control page and API of the interactive seeder; product is not reachable in the test profile. */
@QuarkusTest
class SeederControlPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/")
    URI root;

    @Test
    void servesTheDedicatedPageAndTheSharedAssetsWithoutStoringThem() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (String path : new String[]{"workload-product-seeder/", "workload-product-seeder/index.html"}) {
                HttpResponse<String> page = send(client, "GET", path, null, null);
                assertEquals(200, page.statusCode());
                assertNotStored(page);
                assertTrue(page.body().contains("data-api=\"/api/v1/product-seeder/control\""), page.body());
                for (String asset : new String[]{"href=\"(/workload-control/control\\.css\\?v=[^\"]+)\"",
                        "src=\"(/workload-control/control\\.js\\?v=[^\"]+)\""}) {
                    Matcher reference = Pattern.compile(asset).matcher(page.body());
                    assertTrue(reference.find(), asset);
                    HttpResponse<String> served = send(client, "GET", reference.group(1).substring(1), null, null);
                    assertEquals(200, served.statusCode());
                    assertNotStored(served);
                }
            }
        }
    }

    @Test
    void controlsOnlyThisProcessAndRejectsInvalidUpdatesWithoutChangingTheSetting() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            JsonNode initial = status(send(client, "GET", "api/v1/product-seeder/control", null, null), 200);
            assertEquals("product-seeder", initial.path("workload").asText());
            assertEquals("HTTP requests/s", initial.path("unit").asText());
            assertEquals("IDLE", initial.path("state").asText(), "No run starts without an operator request");
            assertEquals("100", initial.path("defaultRate").asText());
            long revision = initial.path("revision").asLong();

            JsonNode changed = status(send(client, "PUT", "api/v1/product-seeder/control/rate", "application/json",
                    "{\"rate\": 12.5}"), 200);
            assertEquals("12.5", changed.path("targetRate").asText());
            assertEquals(revision + 1, changed.path("revision").asLong());
            assertNotStored(send(client, "GET", "api/v1/product-seeder/control", null, null));

            assertError(send(client, "PUT", "api/v1/product-seeder/control/rate", "application/json",
                    "{\"rate\": 1000.5}"), 400, "INVALID_RATE");
            assertError(send(client, "PUT", "api/v1/product-seeder/control/rate", "application/json",
                    "{\"rate\": 1"), 400, "INVALID_REQUEST");
            assertEquals(415, send(client, "PUT", "api/v1/product-seeder/control/rate", "text/plain",
                    "{\"rate\": 1}").statusCode());
            assertEquals(413, send(client, "PUT", "api/v1/product-seeder/control/rate", "application/json",
                    "{\"rate\": 1" + " ".repeat(2_000) + "}").statusCode());

            JsonNode paused = status(send(client, "POST", "api/v1/product-seeder/control/pause", null, null), 200);
            assertTrue(paused.path("paused").asBoolean());
            assertEquals("12.5", paused.path("targetRate").asText(), "Invalid updates left the setting unchanged");
            assertEquals("0", paused.path("effectiveRate").asText());
            status(send(client, "PUT", "api/v1/product-seeder/control/rate", "application/json", "{\"rate\": 100}"), 200);
            JsonNode resumed = status(send(client, "POST", "api/v1/product-seeder/control/resume", null, null), 200);
            assertEquals("100", resumed.path("effectiveRate").asText());
            assertEquals(revision + 4, resumed.path("revision").asLong());
        }
    }

    @Test
    void startsOneOperatorRunAtATimeThatUsesTheCurrentPauseAndRate() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            status(send(client, "POST", "api/v1/product-seeder/control/pause", null, null), 200);
            JsonNode started = status(send(client, "POST", "api/v1/product-seeder/control/runs", null, null), 202);
            long number = started.path("run").path("number").asLong();
            assertEquals("RUNNING", started.path("run").path("state").asText());
            assertEquals(1056, started.path("run").path("plannedOperations").asLong());
            assertEquals("PAUSED", started.path("state").asText(), "A paused run sends nothing");
            assertError(send(client, "POST", "api/v1/product-seeder/control/runs", null, null), 409, "RUN_ACTIVE");

            status(send(client, "POST", "api/v1/product-seeder/control/resume", null, null), 200);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            JsonNode finished;
            do {
                Thread.sleep(50);
                finished = status(send(client, "GET", "api/v1/product-seeder/control", null, null), 200);
            } while (finished.path("state").asText().equals("RUNNING") && System.nanoTime() - deadline < 0);
            assertEquals("FAILED", finished.path("state").asText(), finished.toString());
            assertEquals(number, finished.path("run").path("number").asLong());
            assertEquals("Create seller catalog-v2.US.seller.1 failed on attempt 1: ConnectException",
                    finished.path("run").path("error").asText());
            assertTrue(finished.path("traffic").get(0).path("errors").asLong() >= 1);
        }
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String type, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(5))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (type != null) {
            request.header("Content-Type", type);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode status(HttpResponse<String> response, int code) throws IOException {
        assertEquals(code, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static void assertError(HttpResponse<String> response, int code, String error) throws IOException {
        assertEquals(error, status(response, code).path("code").asText(), response.body());
    }

    private static void assertNotStored(HttpResponse<String> response) {
        assertTrue(response.headers().allValues("Cache-Control").stream().anyMatch(value -> value.contains("no-store")),
                () -> response.uri() + " " + response.headers());
    }
}
