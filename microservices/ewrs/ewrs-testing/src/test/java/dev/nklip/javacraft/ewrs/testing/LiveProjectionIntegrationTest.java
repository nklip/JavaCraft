package dev.nklip.javacraft.ewrs.testing;

import tools.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioExecutionResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioRunResponse;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class LiveProjectionIntegrationTest extends AbstractEwrsTestingIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    @Test
    void listenNotifyEventuallyProjectsCreatedWorkRequest() {
        ResponseEntity<ScenarioExecutionResponse> scenarioResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/scenarios/CREATE_ONLY/run",
                HttpMethod.POST,
                jsonRequest(null),
                ScenarioExecutionResponse.class
        );

        Assertions.assertEquals(HttpStatus.OK, scenarioResponse.getStatusCode());
        Assertions.assertNotNull(scenarioResponse.getBody());

        ScenarioRunResponse run = lastRun(scenarioResponse.getBody());
        WorkRequestResponse projected = awaitProjectedRequest(run.requestId());

        Assertions.assertNotNull(projected);
        Assertions.assertEquals(EventStatus.CREATED, projected.status());
    }

    @Test
    void sseSubscribersReceiveLiveProjectionUpdates() throws Exception {
        CompletableFuture<String> firstDataLine = new CompletableFuture<>();
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl() + "/api/v1/projections/stream")
                .toURL()
                .openConnection();
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setReadTimeout((int) TIMEOUT.toMillis());
        connection.connect();

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        firstDataLine.complete(line.substring("data:".length()).trim());
                        break;
                    }
                }
            } catch (Exception e) {
                firstDataLine.completeExceptionally(e);
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        try {
            ResponseEntity<ScenarioExecutionResponse> scenarioResponse = restTemplate.exchange(
                    baseUrl() + "/api/v1/scenarios/CREATE_ONLY/run",
                    HttpMethod.POST,
                    jsonRequest(null),
                    ScenarioExecutionResponse.class
            );

            Assertions.assertEquals(HttpStatus.OK, scenarioResponse.getStatusCode());
            Assertions.assertNotNull(scenarioResponse.getBody());
            ScenarioRunResponse run = lastRun(scenarioResponse.getBody());

            String payload = firstDataLine.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Assertions.assertAll(
                    () -> Assertions.assertTrue(payload.contains("\"updateType\":\"WORK_REQUEST_UPDATED\"")),
                    () -> Assertions.assertTrue(payload.contains("\"requestId\":" + run.requestId())),
                    () -> Assertions.assertTrue(payload.contains("\"status\":\"CREATED\""))
            );
        } finally {
            connection.disconnect();
        }
    }

    private WorkRequestResponse awaitProjectedRequest(int requestId) {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() <= deadline) {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl() + "/api/v1/work-requests/" + requestId,
                    HttpMethod.GET,
                    null,
                    String.class
            );
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                try {
                    return objectMapper.readValue(response.getBody(), WorkRequestResponse.class);
                } catch (Exception e) {
                    throw new IllegalStateException("Unable to deserialize projected EWRS work request", e);
                }
            }
            sleepBeforeRetry();
        }
        Assertions.fail("Projected work request %s did not become visible in time".formatted(requestId));
        return null;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for live projection", e);
        }
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpEntity<?> jsonRequest(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private ScenarioRunResponse lastRun(ScenarioExecutionResponse response) {
        Assertions.assertFalse(response.runs().isEmpty(), "Scenario response did not contain any generated runs");
        return response.runs().getLast();
    }
}
