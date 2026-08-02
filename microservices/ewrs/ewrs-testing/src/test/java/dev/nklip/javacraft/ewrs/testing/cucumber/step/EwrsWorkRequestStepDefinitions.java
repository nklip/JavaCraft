package dev.nklip.javacraft.ewrs.testing.cucumber.step;

import dev.nklip.javacraft.ewrs.api.query.BudgetProjectionResponse;
import dev.nklip.javacraft.ewrs.api.query.RebuildProjectionsResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.EventsMonitor;
import dev.nklip.javacraft.ewrs.scenarios.api.RunScenarioRequest;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioExecutionResponse;
import dev.nklip.javacraft.ewrs.scenarios.api.ScenarioRunResponse;
import io.cucumber.java.Before;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

public class EwrsWorkRequestStepDefinitions {

    private static final Duration PROJECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final long POLL_INTERVAL_MILLIS = 100L;

    private final TestRestTemplate restTemplate;
    private final int port;
    private final JdbcTemplate jdbcTemplate;
    private final EventsMonitor eventsMonitor;

    private final Map<String, Integer> requestIds = new HashMap<>();

    private ResponseEntity<ScenarioExecutionResponse> lastScenarioResponse;
    private ResponseEntity<RebuildProjectionsResponse> lastRebuildResponse;
    private int requestCounter;

    @Autowired
    public EwrsWorkRequestStepDefinitions(
            TestRestTemplate restTemplate,
            @LocalServerPort int port,
            JdbcTemplate jdbcTemplate,
            EventsMonitor eventsMonitor
    ) {
        this.restTemplate = restTemplate;
        this.port = port;
        this.jdbcTemplate = jdbcTemplate;
        this.eventsMonitor = eventsMonitor;
    }

    @Before
    public void resetState() {
        jdbcTemplate.execute("truncate table work_request_projection, event_store restart identity cascade");
        jdbcTemplate.execute("alter sequence work_request_id_seq restart with 1000");
        jdbcTemplate.execute("update projection_checkpoint set last_event_store_id = 0 where projection_name = 'ewrs-projector'");
        jdbcTemplate.execute("delete from budget_projection");
        jdbcTemplate.execute("""
                insert into budget_projection (budget_code, initial_budget, reserved_amount, remaining_budget, last_updated_at)
                select budget_code, initial_budget, 0, initial_budget, current_timestamp
                from budget_reference
                order by budget_code
                """);
        eventsMonitor.clear();
        requestIds.clear();
        lastScenarioResponse = null;
        lastRebuildResponse = null;
        requestCounter = 0;
    }

    @When("scenario {string} is executed")
    public void executeScenario(String scenario) {
        executeScenarioInternal("/api/v1/scenarios/%s/run".formatted(scenario), null);
    }

    @When("load is generated with count {int}")
    public void generateLoad(int count) {
        executeScenarioInternal("/api/v1/scenarios/load", new RunScenarioRequest(count, null, null));
    }

    @When("projections are rebuilt")
    public void rebuildProjections() {
        lastRebuildResponse = exchange("/api/v1/admin/projections/rebuild", HttpMethod.POST, null,
                RebuildProjectionsResponse.class);
        Assertions.assertEquals(HttpStatus.OK, lastRebuildResponse.getStatusCode());
        Assertions.assertNotNull(lastRebuildResponse.getBody());
    }

    @Then("the last scenario response has HTTP {int} and final status {string}")
    public void assertLastScenarioResponseStatus(int httpStatus, String status) {
        Assertions.assertNotNull(lastScenarioResponse);
        Assertions.assertNotNull(lastScenarioResponse.getBody());
        ScenarioRunResponse lastRun = lastRun(lastScenarioResponse.getBody());
        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.valueOf(httpStatus), lastScenarioResponse.getStatusCode()),
                () -> Assertions.assertEquals(EventStatus.valueOf(status), lastRun.projectedStatus())
        );
    }

    @Then("the last scenario response has HTTP {int} and {int} runs")
    public void assertLastScenarioResponseRuns(int httpStatus, int runs) {
        Assertions.assertNotNull(lastScenarioResponse);
        Assertions.assertNotNull(lastScenarioResponse.getBody());
        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.valueOf(httpStatus), lastScenarioResponse.getStatusCode()),
                () -> Assertions.assertEquals(runs, lastScenarioResponse.getBody().runs().size())
        );
    }

    @Then("projected work request {string} eventually has status {string}")
    public void projectedWorkRequestEventuallyHasStatus(String alias, String status) {
        EventStatus expectedStatus = EventStatus.valueOf(status);
        WorkRequestResponse projected = await(() -> {
            ResponseEntity<WorkRequestResponse> response = exchange(
                    "/api/v1/work-requests/%s".formatted(requestId(alias)),
                    HttpMethod.GET,
                    null,
                    WorkRequestResponse.class
            );
            return response.getStatusCode() == HttpStatus.OK && response.getBody() != null
                    && response.getBody().status() == expectedStatus ? response.getBody() : null;
        }, "Projected work request %s to reach status %s".formatted(alias, expectedStatus));

        Assertions.assertNotNull(projected);
        Assertions.assertEquals(expectedStatus, projected.status());
    }

    @Then("budget projection {string} eventually has reserved {int} and remaining {int}")
    public void budgetProjectionEventuallyMatches(String budgetCode, int reserved, int remaining) {
        BudgetProjectionResponse projection = await(() -> getBudgetProjection(budgetCode)
                .filter(value -> value.reservedAmount() == reserved && value.remainingBudget() == remaining)
                .orElse(null), "Budget projection %s to become reserved=%s remaining=%s"
                .formatted(budgetCode, reserved, remaining));

        Assertions.assertNotNull(projection);

        Assertions.assertAll(
                () -> Assertions.assertEquals(reserved, projection.reservedAmount()),
                () -> Assertions.assertEquals(remaining, projection.remainingBudget())
        );
    }

    @Then("work request {string} timeline eventually equals statuses {string}")
    public void timelineEventuallyEqualsStatuses(String alias, String csvStatuses) {
        List<EventStatus> expectedStatuses = Arrays.stream(csvStatuses.split(","))
                .map(String::trim)
                .map(EventStatus::valueOf)
                .toList();

        List<WorkRequestTimelineEventResponse> timeline = await(() -> {
            ResponseEntity<List<WorkRequestTimelineEventResponse>> response = exchangeForList(
                    "/api/v1/work-requests/%s/timeline".formatted(requestId(alias)),
                    new ParameterizedTypeReference<>() {
                    }
            );
            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                return null;
            }

            List<EventStatus> actualStatuses = response.getBody().stream()
                    .map(WorkRequestTimelineEventResponse::status)
                    .toList();
            return actualStatuses.equals(expectedStatuses) ? response.getBody() : null;
        }, "Timeline %s to equal %s".formatted(alias, expectedStatuses));

        Assertions.assertNotNull(timeline);
        Assertions.assertEquals(expectedStatuses,
                timeline.stream().map(WorkRequestTimelineEventResponse::status).toList());
    }

    @Then("rebuild response reports {int} events, {int} requests, and {int} budgets")
    public void rebuildResponseReports(int events, int requests, int budgets) {
        Assertions.assertNotNull(lastRebuildResponse);
        Assertions.assertNotNull(lastRebuildResponse.getBody());
        Assertions.assertAll(
                () -> Assertions.assertEquals(events, lastRebuildResponse.getBody().eventsReplayed()),
                () -> Assertions.assertEquals(requests, lastRebuildResponse.getBody().requestsProjected()),
                () -> Assertions.assertEquals(budgets, lastRebuildResponse.getBody().budgetsProjected())
        );
    }

    @Then("projected work request list eventually contains {int} items")
    public void projectedWorkRequestListEventuallyContainsItems(int size) {
        List<WorkRequestResponse> requests = await(() -> {
            ResponseEntity<List<WorkRequestResponse>> response = exchangeForList(
                    "/api/v1/work-requests",
                    new ParameterizedTypeReference<>() {
                    }
            );
            return response.getStatusCode() == HttpStatus.OK && response.getBody() != null
                    && response.getBody().size() == size ? response.getBody() : null;
        }, "Projected work request list to contain %s items".formatted(size));

        Assertions.assertNotNull(requests);
        Assertions.assertEquals(size, requests.size());
    }

    private int requestId(String alias) {
        Integer requestId = requestIds.get(alias);
        Assertions.assertNotNull(requestId, "Unknown request alias: " + alias);
        return requestId;
    }

    private void executeScenarioInternal(String path, RunScenarioRequest request) {
        lastScenarioResponse = exchange(path, HttpMethod.POST, request, ScenarioExecutionResponse.class);
        Assertions.assertEquals(HttpStatus.OK, lastScenarioResponse.getStatusCode());
        Assertions.assertNotNull(lastScenarioResponse.getBody());
        rememberRuns(lastScenarioResponse.getBody());
    }

    private void rememberRuns(ScenarioExecutionResponse response) {
        for (ScenarioRunResponse run : response.runs()) {
            requestIds.put("last", run.requestId());
            requestIds.put("request-" + (++requestCounter), run.requestId());
        }
    }

    private ScenarioRunResponse lastRun(ScenarioExecutionResponse response) {
        Assertions.assertFalse(response.runs().isEmpty(), "Scenario response did not contain any generated runs");
        return response.runs().getLast();
    }

    private java.util.Optional<BudgetProjectionResponse> getBudgetProjection(String budgetCode) {
        ResponseEntity<List<BudgetProjectionResponse>> response = exchangeForList(
                "/api/v1/projections/budgets",
                new ParameterizedTypeReference<>() {
                }
        );
        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            return java.util.Optional.empty();
        }
        return response.getBody().stream()
                .filter(projection -> projection.budgetCode().equals(budgetCode))
                .findFirst();
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object body, Class<T> responseType) {
        return restTemplate.exchange(baseUrl() + path, method, jsonRequest(body), responseType);
    }

    private <T> ResponseEntity<List<T>> exchangeForList(String path, ParameterizedTypeReference<List<T>> responseType) {
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, null, responseType);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpEntity<?> jsonRequest(@Nullable Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private <T> T await(AwaitCondition<T> supplier, String description) {
        long deadline = System.currentTimeMillis() + PROJECTION_TIMEOUT.toMillis();
        while (System.currentTimeMillis() <= deadline) {
            try {
                T value = supplier.get();
                if (value != null) {
                    return value;
                }
            } catch (Exception ignored) {
                // keep polling until the projection catches up
            }
            sleepBeforeRetry();
        }
        Assertions.fail("Timed out waiting for " + description);
        throw new IllegalStateException("Unreachable after failing to await " + description);
    }

    @FunctionalInterface
    private interface AwaitCondition<T> {
        @Nullable T get() throws Exception;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for EWRS projections", e);
        }
    }
}
