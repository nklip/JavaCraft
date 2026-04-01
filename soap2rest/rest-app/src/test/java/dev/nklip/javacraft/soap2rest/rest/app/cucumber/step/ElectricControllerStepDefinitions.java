package dev.nklip.javacraft.soap2rest.rest.app.cucumber.step;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.app.dao.entity.ElectricMetric;
import dev.nklip.javacraft.soap2rest.rest.app.security.AuthenticationService;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

/**
 * This test shows how to create HTTP requests <b>without</b> <i>io.rest-assured:rest-assured</i> library.
 */
@Scope(SCOPE_CUCUMBER_GLUE)
@RequiredArgsConstructor
public class ElectricControllerStepDefinitions {

    @LocalServerPort
    int port;

    private HttpEntity<String> prepareHttpEntity() {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");
        return new HttpEntity<>(null, headers);
    }

    private HttpEntity<String> prepareJsonHttpEntity(String jsonBody) {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        headers.set(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");
        return new HttpEntity<>(jsonBody, headers);
    }

    private HttpEntity<ElectricMetric> submitElectricReading(
            Long accountId, Long meterId, BigDecimal reading, String date) {
        String jsonBody = """
        {
            "meterId": %s,
            "reading":"%s",
            "date": "%s"
        }
        """.formatted(meterId, reading, date);

        HttpEntity<String> entity = prepareJsonHttpEntity(jsonBody);

        RestTemplate restTemplate = new RestTemplate();
        return restTemplate.exchange(
                "http://localhost:%s/api/v1/smart/%s/electric".formatted(port, accountId),
                HttpMethod.PUT,
                entity,
                ElectricMetric.class
        );
    }

    private List<Metric> getElectricMetrics(Long accountId) {
        HttpEntity<String> entity = prepareHttpEntity();
        RestTemplate restTemplate = new RestTemplate();

        ResponseEntity<List<Metric>> httpResponse = restTemplate.exchange(
                "http://localhost:%s/api/v1/smart/%s/electric".formatted(port, accountId),
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
        );

        Assertions.assertNotNull(httpResponse);
        Assertions.assertNotNull(httpResponse.getBody());

        return httpResponse.getBody();
    }

    @Given("the account {long} doesn't have electric metrics")
    public void cleanElectricMetrics(Long accountId) {
        HttpEntity<String> entity = prepareHttpEntity();
        RestTemplate restTemplate = new RestTemplate();

        HttpEntity<Integer> httpResponse = restTemplate.exchange(
                "http://localhost:%s/api/v1/smart/%s/electric".formatted(port, accountId),
                HttpMethod.DELETE,
                entity,
                Integer.class
        );

        Assertions.assertNotNull(httpResponse);
        Assertions.assertNotNull(httpResponse.getBody());
        Assertions.assertTrue(httpResponse.getBody() >= 0);
    }

    @When("an account {long} submits a PUT request with a new electric reading: {long}, {bigdecimal}, {string}")
    public void applyPutRequestWithElectricReading(
            Long accountId, Long meterId, BigDecimal reading, String date) {
        HttpEntity<ElectricMetric> httpResponse = submitElectricReading(accountId, meterId, reading, date);

        Assertions.assertNotNull(httpResponse);
        Assertions.assertNotNull(httpResponse.getBody());
        Assertions.assertEquals(meterId, httpResponse.getBody().getMeterId());
        Assertions.assertEquals(reading, httpResponse.getBody().getReading());
        Assertions.assertEquals(date, httpResponse.getBody().getDate().toString());
    }

    @When("an account {long} submits electric metrics")
    public void applyPutRequestWithElectricMetrics(Long accountId, DataTable table) {
        for (Map<String, String> row : table.asMaps(String.class, String.class)) {
            applyPutRequestWithElectricReading(
                    accountId,
                    Long.parseLong(row.get("meterId")),
                    new BigDecimal(row.get("reading")),
                    row.get("date")
            );
        }
    }

    @Then("check the latest electric reading for the account = {long} and meterId = {long} is equal = {bigdecimal}")
    public void checkLatestElectricReading(Long accountId, Long meterId, BigDecimal reading) {
        Metric latestMetric = getElectricMetrics(accountId)
                .stream()
                .filter(metric -> meterId.equals(metric.getMeterId()))
                .max(Metric::compareTo)
                .orElseThrow();
        Assertions.assertEquals(0, latestMetric.getReading().compareTo(reading));
    }

    @Then("account {long} has no latest electric metric")
    public void checkNoLatestElectricMetric(Long accountId) {
        HttpEntity<String> entity = prepareHttpEntity();
        RestTemplate restTemplate = new RestTemplate();

        HttpEntity<Metric> httpResponse = restTemplate.exchange(
                "http://localhost:%s/api/v1/smart/%s/electric/latest".formatted(port, accountId),
                HttpMethod.GET,
                entity,
                Metric.class
        );

        Assertions.assertNotNull(httpResponse);
        Assertions.assertNull(httpResponse.getBody());
    }

    @Then("account {long} has electric metrics list size {int}")
    public void checkElectricMetricListSize(Long accountId, Integer expectedSize) {
        Assertions.assertEquals(expectedSize, getElectricMetrics(accountId).size());
    }

}
