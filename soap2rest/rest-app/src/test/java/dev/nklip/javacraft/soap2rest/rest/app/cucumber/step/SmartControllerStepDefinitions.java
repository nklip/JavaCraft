package dev.nklip.javacraft.soap2rest.rest.app.cucumber.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.soap2rest.rest.api.AsyncJobResultResponse;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import dev.nklip.javacraft.soap2rest.rest.app.security.AuthenticationService;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
public class SmartControllerStepDefinitions {

    private static final long ASYNC_TIMEOUT_MILLIS = 5_000L;
    private static final long ASYNC_POLL_INTERVAL_MILLIS = 100L;

    @LocalServerPort
    int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String lastAsyncRequestId;

    private RequestSpecification prepareBaseRequest(Long accountId) {
        RequestSpecification request = RestAssured.given();
        request.baseUri("http://localhost:%s/api/v1/smart/%s".formatted(port, accountId));
        request.header(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");

        return request;
    }

    private HttpEntity<String> prepareHttpEntity(String jsonBody) {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        return new HttpEntity<>(jsonBody, headers);
    }

    @Given("the account {long} doesn't have any metrics")
    public void cleanGasMetrics(Long accountId) {
        RequestSpecification request = prepareBaseRequest(accountId);

        Response response = request.delete();

        int deleted = Integer.parseInt(response.asString());
        Assertions.assertTrue(deleted >= 0);
    }

    @When("an account {long} submits a PUT request with new metrics")
    public void applyPutRequestWithGasReading(Long accountId, DataTable table) throws Exception {
        Metrics metrics = data2Metrics(accountId, table);

        String jsonBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics);

        log.info(jsonBody);

        HttpEntity<String> entity = prepareHttpEntity(jsonBody);

        RestTemplate restTemplate = new RestTemplate();

        HttpEntity<Boolean> httpResponse = restTemplate.exchange(
                "http://localhost:%s/api/v1/smart/%s".formatted(port, accountId),
                HttpMethod.PUT,
                entity,
                Boolean.class
        );
        Boolean response = httpResponse.getBody();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response);
    }

    @When("an account {long} submits an ASYNC PUT request with new metrics")
    public void applyAsyncPutRequestWithMetrics(Long accountId, DataTable table) throws Exception {
        Metrics metrics = data2Metrics(accountId, table);
        String jsonBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics);

        Response response = prepareBaseRequest(accountId)
                .queryParam("async", true)
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(jsonBody)
                .put();

        Assertions.assertEquals(202, response.statusCode());
        lastAsyncRequestId = response.asString();
        Assertions.assertNotNull(lastAsyncRequestId);
        Assertions.assertFalse(lastAsyncRequestId.isBlank());
    }

    @Then("polling the async smart request eventually returns COMPLETED")
    public void pollingAsyncRequestEventuallyReturnsCompleted() throws Exception {
        Response response = pollAsyncResultUntilFinished();

        Assertions.assertEquals(200, response.statusCode());
        AsyncJobResultResponse asyncJobResultResponse = objectMapper.readValue(
                response.asString(),
                AsyncJobResultResponse.class
        );
        Assertions.assertEquals(lastAsyncRequestId, asyncJobResultResponse.getRequestId());
        Assertions.assertEquals(Boolean.TRUE, asyncJobResultResponse.getResult());
        Assertions.assertNull(asyncJobResultResponse.getErrorMessage());
    }

    @Then("polling the async smart request eventually returns FAILED with message {string}")
    public void pollingAsyncRequestEventuallyReturnsFailed(String expectedMessage) throws Exception {
        Response response = pollAsyncResultUntilFinished();

        Assertions.assertEquals(500, response.statusCode());
        AsyncJobResultResponse asyncJobResultResponse = objectMapper.readValue(
                response.asString(),
                AsyncJobResultResponse.class
        );
        Assertions.assertEquals(lastAsyncRequestId, asyncJobResultResponse.getRequestId());
        Assertions.assertNull(asyncJobResultResponse.getResult());
        Assertions.assertEquals(expectedMessage, asyncJobResultResponse.getErrorMessage());
    }

    private Metrics data2Metrics(Long accountId, DataTable table) {
        Metrics metrics = new Metrics();
        metrics.setAccountId(accountId);

        List<Metric> gasMetricList = new ArrayList<>();
        List<Metric> electricMetricsList = new ArrayList<>();

        for (Map<String, String> row : table.asMaps(String.class, String.class)) {
            String type = row.get("type");

            Metric metric = new Metric();
            metric.setMeterId(Long.parseLong(row.get("meterId")));
            metric.setReading(new BigDecimal(row.get("reading")));
            metric.setDate(Date.valueOf(row.get("date")));

            if (type.equalsIgnoreCase("gas")) {
                gasMetricList.add(metric);
            } else if (type.equalsIgnoreCase("ele")) {
                electricMetricsList.add(metric);
            }
        }

        metrics.setGasReadings(gasMetricList);
        metrics.setElecReadings(electricMetricsList);

        return metrics;
    }

    private Response pollAsyncResultUntilFinished() {
        Assertions.assertNotNull(lastAsyncRequestId);
        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MILLIS;
        Response response = null;

        while (System.currentTimeMillis() <= deadline) {
            response = RestAssured.given()
                    .baseUri("http://localhost:%s/api/v1/smart/async/%s".formatted(port, lastAsyncRequestId))
                    .header(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo")
                    .get();

            if (response.statusCode() != 202) {
                return response;
            }

            sleepBeforeNextPoll();
        }

        Assertions.fail("Timed out waiting for async smart request completion");
        return response;
    }

    private void sleepBeforeNextPoll() {
        try {
            Thread.sleep(ASYNC_POLL_INTERVAL_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling async smart request", ex);
        }
    }

}
