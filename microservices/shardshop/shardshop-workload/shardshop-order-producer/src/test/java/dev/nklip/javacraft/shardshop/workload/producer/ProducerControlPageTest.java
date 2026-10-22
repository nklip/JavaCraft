package dev.nklip.javacraft.shardshop.workload.producer;

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

/** The packaged control page and API of the producer. The test application does not start the producer. */
@QuarkusTest
class ProducerControlPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/")
    URI root;

    @Test
    void servesTheDedicatedPageAndTheSharedAssetsWithoutStoringThem() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (String path : new String[]{"workload-order-producer/", "workload-order-producer/index.html"}) {
                HttpResponse<String> page = send(client, "GET", path, null, null);
                assertEquals(200, page.statusCode());
                assertNotStored(page);
                assertTrue(page.body().contains("data-api=\"/api/v1/order-producer/control\""), page.body());
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
            JsonNode initial = status(send(client, "GET", "api/v1/order-producer/control", null, null), 200);
            assertEquals("order-producer", initial.path("workload").asText());
            assertEquals("new order starts/s", initial.path("unit").asText());
            assertEquals("WAITING", initial.path("state").asText(), "The test application never starts the producer");
            assertEquals("0.5", initial.path("defaultRate").asText());
            long revision = initial.path("revision").asLong();

            JsonNode changed = status(send(client, "PUT", "api/v1/order-producer/control/rate", "application/json",
                    "{\"rate\": 12.5}"), 200);
            assertEquals("12.5", changed.path("targetRate").asText());
            assertEquals(revision + 1, changed.path("revision").asLong());
            assertNotStored(send(client, "GET", "api/v1/order-producer/control", null, null));

            assertError(send(client, "PUT", "api/v1/order-producer/control/rate", "application/json",
                    "{\"rate\": 100.5}"), 400, "INVALID_RATE");
            assertError(send(client, "PUT", "api/v1/order-producer/control/rate", "application/json",
                    "{\"rate\": 1"), 400, "INVALID_REQUEST");
            assertEquals(415, send(client, "PUT", "api/v1/order-producer/control/rate", "text/plain",
                    "{\"rate\": 1}").statusCode());
            assertEquals(413, send(client, "PUT", "api/v1/order-producer/control/rate", "application/json",
                    "{\"rate\": 1" + " ".repeat(2_000) + "}").statusCode());
            assertEquals(404, send(client, "POST", "api/v1/order-producer/control/runs", null, null).statusCode(),
                    "Only the seeder has runs");

            JsonNode paused = status(send(client, "POST", "api/v1/order-producer/control/pause", null, null), 200);
            assertTrue(paused.path("paused").asBoolean());
            assertEquals("12.5", paused.path("targetRate").asText(), "Invalid updates left the setting unchanged");
            assertEquals("0", paused.path("effectiveRate").asText());
            status(send(client, "PUT", "api/v1/order-producer/control/rate", "application/json", "{\"rate\": 0.5}"), 200);
            JsonNode resumed = status(send(client, "POST", "api/v1/order-producer/control/resume", null, null), 200);
            assertEquals("0.5", resumed.path("effectiveRate").asText());
            assertEquals(revision + 4, resumed.path("revision").asLong());
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
