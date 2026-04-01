package dev.nklip.javacraft.soap2rest.rest.app.cucumber.step;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.nklip.javacraft.soap2rest.rest.app.dao.entity.Meter;
import dev.nklip.javacraft.soap2rest.rest.app.security.AuthenticationService;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Scope(SCOPE_CUCUMBER_GLUE)
public class MeterControllerStepDefinitions {

    private static final String METERS_PATH = "http://localhost:%s/api/v1/smart/%s/meters";

    @LocalServerPort
    int port;

    private final Map<Long, List<Long>> createdMeterIdsByAccount = new HashMap<>();
    private final Map<Long, String> lastManufacturerByAccount = new HashMap<>();

    private HttpEntity<String> prepareHttpEntity() {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");
        return new HttpEntity<>(null, headers);
    }

    private HttpEntity<String> prepareJsonHttpEntity(String jsonBody) {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(AuthenticationService.AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return new HttpEntity<>(jsonBody, headers);
    }

    private List<Meter> getMeters(Long accountId) {
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<List<Meter>> response = restTemplate.exchange(
                METERS_PATH.formatted(port, accountId),
                HttpMethod.GET,
                prepareHttpEntity(),
                new ParameterizedTypeReference<>() {}
        );

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        return response.getBody();
    }

    private Meter createMeter(Long accountId, String manufacturer) {
        RestTemplate restTemplate = new RestTemplate();
        String jsonBody = """
                {
                  "manufacturer":"%s"
                }
                """.formatted(manufacturer);

        ResponseEntity<Meter> response = restTemplate.exchange(
                METERS_PATH.formatted(port, accountId),
                HttpMethod.PUT,
                prepareJsonHttpEntity(jsonBody),
                Meter.class
        );

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        return response.getBody();
    }

    private Meter updateMeter(Long accountId, Long meterId, String manufacturer) {
        RestTemplate restTemplate = new RestTemplate();
        String jsonBody = """
                {
                  "manufacturer":"%s"
                }
                """.formatted(manufacturer);

        ResponseEntity<Meter> response = restTemplate.exchange(
                METERS_PATH.formatted(port, accountId) + "/" + meterId,
                HttpMethod.PUT,
                prepareJsonHttpEntity(jsonBody),
                Meter.class
        );

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        return response.getBody();
    }

    private Meter getMeter(Long accountId, Long meterId) {
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Meter> response = restTemplate.exchange(
                METERS_PATH.formatted(port, accountId) + "/" + meterId,
                HttpMethod.GET,
                prepareHttpEntity(),
                Meter.class
        );

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        return response.getBody();
    }

    private int deleteAllMeters(Long accountId) {
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Integer> response = restTemplate.exchange(
                METERS_PATH.formatted(port, accountId),
                HttpMethod.DELETE,
                prepareHttpEntity(),
                Integer.class
        );

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        return response.getBody();
    }

    private int deleteMeter(Long accountId, Long meterId) {
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Integer> response = restTemplate.exchange(
                METERS_PATH.formatted(port, accountId) + "/" + meterId,
                HttpMethod.DELETE,
                prepareHttpEntity(),
                Integer.class
        );

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        return response.getBody();
    }

    private Long getFirstCreatedMeterId(Long accountId) {
        List<Long> ids = createdMeterIdsByAccount.get(accountId);
        Assertions.assertNotNull(ids);
        Assertions.assertFalse(ids.isEmpty());
        return ids.getFirst();
    }

    private Long getLastCreatedMeterId(Long accountId) {
        List<Long> ids = createdMeterIdsByAccount.get(accountId);
        Assertions.assertNotNull(ids);
        Assertions.assertFalse(ids.isEmpty());
        return ids.getLast();
    }

    private void trackCreatedMeter(Long accountId, Meter meter) {
        createdMeterIdsByAccount.computeIfAbsent(accountId, key -> new ArrayList<>()).add(meter.getId());
        lastManufacturerByAccount.put(accountId, meter.getManufacturer());
    }

    @Given("account {long} doesn't have meters")
    public void ensureAccountHasNoMeters(Long accountId) {
        int deletedCount = deleteAllMeters(accountId);
        Assertions.assertTrue(deletedCount >= 0);
        createdMeterIdsByAccount.remove(accountId);
        lastManufacturerByAccount.remove(accountId);
        Assertions.assertEquals(0, getMeters(accountId).size());
    }

    @When("account {long} submits a new meter with manufacturer {string}")
    public void createSimpleMeter(Long accountId, String manufacturer) {
        Meter meter = createMeter(accountId, manufacturer);

        Assertions.assertNotNull(meter.getId());
        Assertions.assertEquals(accountId, meter.getAccountId());
        Assertions.assertEquals(manufacturer, meter.getManufacturer());
        trackCreatedMeter(accountId, meter);
    }

    @When("account {long} updates the last created meter with manufacturer {string}")
    public void updateLastCreatedMeter(Long accountId, String manufacturer) {
        Long meterId = getLastCreatedMeterId(accountId);
        Meter updatedMeter = updateMeter(accountId, meterId, manufacturer);

        Assertions.assertEquals(meterId, updatedMeter.getId());
        Assertions.assertEquals(accountId, updatedMeter.getAccountId());
        Assertions.assertEquals(manufacturer, updatedMeter.getManufacturer());
        lastManufacturerByAccount.put(accountId, updatedMeter.getManufacturer());
    }

    @When("account {long} deletes the first created meter")
    public void deleteFirstCreatedMeter(Long accountId) {
        Long firstMeterId = getFirstCreatedMeterId(accountId);
        int deleted = deleteMeter(accountId, firstMeterId);
        Assertions.assertEquals(1, deleted);

        List<Long> ids = createdMeterIdsByAccount.get(accountId);
        Assertions.assertNotNull(ids);
        ids.remove(firstMeterId);
    }

    @When("account {long} deletes all meters")
    public void deleteAllMetersForAccount(Long accountId) {
        int deleted = deleteAllMeters(accountId);
        Assertions.assertTrue(deleted >= 0);
        createdMeterIdsByAccount.remove(accountId);
        lastManufacturerByAccount.remove(accountId);
    }

    @Then("account {long} has meters list size {int}")
    public void checkMetersSize(Long accountId, Integer expectedSize) {
        Assertions.assertEquals(expectedSize, getMeters(accountId).size());
    }

    @Then("the last created meter for account {long} has manufacturer {string}")
    public void checkLastCreatedMeterManufacturer(Long accountId, String expectedManufacturer) {
        Assertions.assertEquals(expectedManufacturer, lastManufacturerByAccount.get(accountId));
    }

    @Then("account {long} can get the last created meter")
    public void checkCanGetLastCreatedMeter(Long accountId) {
        Long meterId = getLastCreatedMeterId(accountId);
        Meter meter = getMeter(accountId, meterId);

        Assertions.assertEquals(meterId, meter.getId());
        Assertions.assertEquals(accountId, meter.getAccountId());
        Assertions.assertEquals(lastManufacturerByAccount.get(accountId), meter.getManufacturer());
    }

    @Then("account {long} cannot access account {long} first created meter")
    public void checkAccountIsolationForMeterRead(Long callerAccountId, Long ownerAccountId) {
        Long ownerMeterId = getFirstCreatedMeterId(ownerAccountId);
        RestTemplate restTemplate = new RestTemplate();

        HttpClientErrorException.BadRequest exception = Assertions.assertThrows(
                HttpClientErrorException.BadRequest.class,
                () -> restTemplate.exchange(
                        METERS_PATH.formatted(port, callerAccountId) + "/" + ownerMeterId,
                        HttpMethod.GET,
                        prepareHttpEntity(),
                        Meter.class
                )
        );

        Assertions.assertTrue(exception.getResponseBodyAsString().contains("Meter is not linked to account."));
    }
}
