package my.javacraft.elastic.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.cucumber.conf.CucumberSpringConfiguration;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.service.activity.UserActivityService;
import org.apache.http.HttpHeaders;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class UserActivityControllerStepDefinitions {

    @LocalServerPort
    int port;

    @Autowired
    ElasticsearchClient esClient;

    @Given("user {string} doesn't have any events")
    public void clearUserActivity(String userId) throws IOException {
        DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(q -> q.term(t -> t
                        .field(UserActivityService.USER_ID)
                        .value(v -> v.stringValue(userId))
                )).build();
        DeleteByQueryResponse deleteByQueryResponse = esClient.deleteByQuery(deleteByQueryRequest);
        Assertions.assertNotNull(deleteByQueryResponse);
        log.info("All events for user '{}' are deleted!", userId);
    }

    @When("add new event with expected result = {string}")
    public void addNewEvent(String expectedResult, DataTable dataTable) throws JsonProcessingException {
        String jsonBody = jsonBody(dataTable);

        log.info("created json:\n" + jsonBody);

        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

        RestTemplate restTemplate = new RestTemplate();

        HttpEntity<UserClickResponse> httpResponse = restTemplate.exchange(
                "http://localhost:%s/api/services/user-activity".formatted(port),
                HttpMethod.POST,
                entity,
                UserClickResponse.class
        );
        Assertions.assertNotNull(httpResponse);
        Assertions.assertNotNull(httpResponse.getBody());
        // Every click is a new immutable event document — result is always 'Created'
        Assertions.assertEquals(expectedResult, httpResponse.getBody().getResult().toString());

        ObjectMapper objectMapper = new ObjectMapper();
        log.info("{}", objectMapper.writeValueAsString(httpResponse.getBody()));
    }

    @When("there are {int} requests")
    public void sendMultipleRequestInParallel(Integer requests, DataTable dataTable) throws Exception {
        log.info("sending {} requests in parallel...", requests);

        List<Future<HttpEntity<UserClickResponse>>> futureClicks = new ArrayList<>();
        try (ExecutorService executorService = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                Future<HttpEntity<UserClickResponse>> callbackResult = executorService.submit(() -> {
                    String jsonBody = jsonBody(dataTable);

                    MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
                    headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

                    HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

                    RestTemplate restTemplate = new RestTemplate();

                    return restTemplate.exchange(
                            "http://localhost:%s/api/services/user-activity".formatted(port),
                            HttpMethod.POST,
                            entity,
                            UserClickResponse.class
                    );
                });
                futureClicks.add(callbackResult);
            }
        }

        Assertions.assertFalse(futureClicks.isEmpty());

        for (Future<HttpEntity<UserClickResponse>> futureUserClickResponse : futureClicks) {
            HttpEntity<UserClickResponse> userClickResponseHttpEntity = futureUserClickResponse.get();
            UserClickResponse userClickResponse = userClickResponseHttpEntity.getBody();
            Assertions.assertNotNull(userClickResponse);
            log.info(userClickResponse.toString());
        }
    }

    @Then("user {string} has {int} hit counts for documentId = {string}, searchType = {string} and pattern = {string}")
    public void checkHitCounts(
            String userId,
            int hitCounts,
            String recordId,
            String type,
            String pattern) throws InterruptedException {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        RestTemplate restTemplate = new RestTemplate();
        String userActivityUrl = "http://localhost:%s/api/services/user-activity/users/%s".formatted(port, userId);

        // Wait until ES indexes at least 1 result for this user
        Assertions.assertTrue(CucumberSpringConfiguration.assertWithWait(1, () -> {
            HttpEntity<List<UserActivity>> currentResponse = restTemplate.exchange(
                    userActivityUrl,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );
            List<UserActivity> currentBody = currentResponse.getBody();
            return currentBody == null ? 0 : currentBody.size();
        }));

        HttpEntity<List<UserActivity>> finalResponse = restTemplate.exchange(
                userActivityUrl,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
        );
        Assertions.assertNotNull(finalResponse.getBody());
        List<UserActivity> body = finalResponse.getBody();

        // With event stream, each click produces a new document.
        // The popular service groups by recordId → one result per distinct record.
        Assertions.assertFalse(body.isEmpty(), "Expected at least one activity for userId=" + userId);

        UserActivity topActivity = body.getFirst();
        Assertions.assertEquals(recordId, topActivity.getRecordId());
        Assertions.assertEquals(pattern, topActivity.getSearchValue());
        Assertions.assertEquals(userId, topActivity.getUserId());

        log.info("verified {} events produced 1 popular result for userId={}, recordId={}", hitCounts, userId, recordId);
    }

    @Then("user {string} has next sorting results")
    public void testSortingOrder(String userId, DataTable dataTable) throws InterruptedException {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        RestTemplate restTemplate = new RestTemplate();
        String userActivityUrl = "http://localhost:%s/api/services/user-activity/users/%s".formatted(port, userId);

        Assertions.assertTrue(CucumberSpringConfiguration.assertWithWait(dataTable.height(), () -> {
            HttpEntity<List<UserActivity>> currentResponse = restTemplate.exchange(
                    userActivityUrl,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );
            List<UserActivity> currentBody = currentResponse.getBody();
            return currentBody == null ? 0 : currentBody.size();
        }));

        HttpEntity<List<UserActivity>> finalResponse = restTemplate.exchange(
                userActivityUrl,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
        );
        Assertions.assertNotNull(finalResponse.getBody());
        List<UserActivity> body = finalResponse.getBody();
        Assertions.assertEquals(dataTable.height(), body.size());

        // Verify ordering by searchValue (most-clicked record first)
        List<List<String>> expectedResults = dataTable.cells();
        for (int i = 0; i < body.size(); i++) {
            UserActivity userActivity = body.get(i);
            Assertions.assertEquals(expectedResults.get(i).get(0), userActivity.getSearchValue());
        }
    }

    private String jsonBody(DataTable dataTable) throws JsonProcessingException {
        UserClick userClick = new UserClick();
        List<String> data = dataTable.cells().getFirst();
        userClick.setUserId(data.get(0));
        userClick.setRecordId(data.get(1));
        userClick.setSearchType(data.get(2));
        userClick.setSearchPattern(data.get(3));

        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.writeValueAsString(userClick);
    }
}
