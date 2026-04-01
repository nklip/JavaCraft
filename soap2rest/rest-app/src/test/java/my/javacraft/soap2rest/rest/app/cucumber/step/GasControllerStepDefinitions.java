package my.javacraft.soap2rest.rest.app.cucumber.step;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import my.javacraft.soap2rest.rest.api.Metric;
import my.javacraft.soap2rest.rest.app.dao.entity.GasMetric;
import my.javacraft.soap2rest.rest.app.security.AuthenticationService;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.restassured.RestAssured;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Scope(SCOPE_CUCUMBER_GLUE)
public class GasControllerStepDefinitions {

    @LocalServerPort
    int port;

    private RequestSpecification prepareBaseRequest(Long accountId) {
        RequestSpecification request = RestAssured.given();
        request.baseUri("http://localhost:%s/api/v1/smart/%s/gas".formatted(port, accountId));
        request.header(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");

        return request;
    }

    private HttpEntity<String> prepareHttpEntity() {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");
        return new HttpEntity<>(null, headers);
    }

    private List<Metric> getGasMetrics(Long accountId) {
        HttpEntity<String> entity = prepareHttpEntity();
        RestTemplate restTemplate = new RestTemplate();

        ResponseEntity<List<Metric>> httpResponse = restTemplate.exchange(
                "http://localhost:%s/api/v1/smart/%s/gas".formatted(port, accountId),
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
        );

        Assertions.assertNotNull(httpResponse);
        Assertions.assertNotNull(httpResponse.getBody());
        return httpResponse.getBody();
    }

    @Given("the account {long} doesn't have gas metrics")
    public void cleanGasMetrics(Long accountId) {
        RequestSpecification request = prepareBaseRequest(accountId);

        Response response = request.delete();

        int result = Integer.parseInt(response.asString());
        Assertions.assertTrue(result >= 0);
    }

    @When("an account {long} submits a PUT request with a new gas reading: {long}, {bigdecimal}, {string}")
    public void applyPutRequestWithGasReading(
            Long accountId, Long meterId, BigDecimal reading, String date) {
        RequestSpecification request = prepareBaseRequest(accountId);
        request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        String jsonBody = """
        {
            "meterId": %s,
            "reading":"%s",
            "date": "%s"
        }
        """.formatted(meterId, reading, date);

        Response response = request.body(jsonBody).put();
        Assertions.assertEquals(200, response.statusCode());

        GasMetric metric = response.as(GasMetric.class);

        Assertions.assertNotNull(metric);
        Assertions.assertEquals(meterId, metric.getMeterId());
        Assertions.assertEquals(reading, metric.getReading());
        Assertions.assertEquals(date, metric.getDate().toString());
    }

    @When("an account {long} submits gas metrics")
    public void applyPutRequestWithGasMetrics(Long accountId, DataTable table) {
        for (Map<String, String> row : table.asMaps(String.class, String.class)) {
            applyPutRequestWithGasReading(
                    accountId,
                    Long.parseLong(row.get("meterId")),
                    new BigDecimal(row.get("reading")),
                    row.get("date")
            );
        }
    }

    @Then("check the latest gas reading for the account = {long} and meterId = {long} is equal = {bigdecimal}")
    public void checkLatestGasReading(Long accountId, Long meterId, BigDecimal reading) {
        Metric latestMetric = getGasMetrics(accountId)
                .stream()
                .filter(metric -> meterId.equals(metric.getMeterId()))
                .max(Metric::compareTo)
                .orElseThrow();
        Assertions.assertEquals(0, latestMetric
                .getReading()
                .compareTo(reading)
        );
    }

    @Then("account {long} has no latest gas metric")
    public void checkNoLatestGasMetric(Long accountId) {
        HttpEntity<String> entity = prepareHttpEntity();
        RestTemplate restTemplate = new RestTemplate();

        HttpEntity<Metric> httpResponse = restTemplate.exchange(
                "http://localhost:%s/api/v1/smart/%s/gas/latest".formatted(port, accountId),
                HttpMethod.GET,
                entity,
                Metric.class
        );

        Assertions.assertNotNull(httpResponse);
        Assertions.assertNull(httpResponse.getBody());
    }

    @Then("account {long} has gas metrics list size {int}")
    public void checkGasMetricListSize(Long accountId, Integer expectedSize) {
        Assertions.assertEquals(expectedSize, getGasMetrics(accountId).size());
    }

}
