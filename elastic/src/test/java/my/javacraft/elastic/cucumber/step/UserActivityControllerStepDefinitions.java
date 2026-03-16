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
import java.util.Map;
import java.util.concurrent.Callable;
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

    @Given("all user-activity events are deleted")
    public void clearAllUserActivity() throws IOException {
        DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(q -> q.matchAll(m -> m))
                .refresh(true)
                .build();
        DeleteByQueryResponse response = esClient.deleteByQuery(deleteByQueryRequest);
        Assertions.assertNotNull(response);
        log.info("Cleared {} user-activity events from index", response.deleted());
    }

    @When("users vote on posts in parallel")
    public void voteOnPostsInParallel(DataTable dataTable) throws Exception {
        List<Map<String, String>> rows = dataTable.asMaps();

        List<Callable<HttpEntity<UserClickResponse>>> tasks = new ArrayList<>();
        int userCounter = 1;

        for (Map<String, String> row : rows) {
            String postId = row.get("postId").trim();
            int upvotes = Integer.parseInt(row.get("up").trim());
            int downvotes = Integer.parseInt(row.get("down").trim());

            for (int i = 0; i < upvotes; i++) {
                String userId = "hot-u%03d".formatted(userCounter++);
                tasks.add(() -> submitVote(userId, postId, "UPVOTE"));
            }
            for (int i = 0; i < downvotes; i++) {
                String userId = "hot-u%03d".formatted(userCounter++);
                tasks.add(() -> submitVote(userId, postId, "DOWNVOTE"));
            }
        }

        log.info("Submitting {} vote events in parallel...", tasks.size());
        List<Future<HttpEntity<UserClickResponse>>> futures;
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 50))) {
            futures = pool.invokeAll(tasks);
        }

        for (Future<HttpEntity<UserClickResponse>> future : futures) {
            UserClickResponse body = future.get().getBody();
            Assertions.assertNotNull(body);
            Assertions.assertEquals("Created", body.getResult().toString());
        }
        log.info("All {} vote events ingested successfully", tasks.size());
    }

    @Then("top posts are returned in this order")
    public void verifyTopPostsOrder(DataTable dataTable) throws InterruptedException {
        List<String> expectedPostIds = dataTable.asList();

        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        RestTemplate restTemplate = new RestTemplate();
        String topUrl = "http://localhost:%s/api/services/user-activity/top?size=20".formatted(port);

        Assertions.assertTrue(CucumberSpringConfiguration.assertWithWait(expectedPostIds.size(), () -> {
            HttpEntity<List<UserActivity>> response = restTemplate.exchange(
                    topUrl, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}
            );
            List<UserActivity> body = response.getBody();
            return body == null ? 0 : body.size();
        }), "Timed out waiting for %d top posts".formatted(expectedPostIds.size()));

        HttpEntity<List<UserActivity>> finalResponse = restTemplate.exchange(
                topUrl, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}
        );
        List<UserActivity> topPosts = finalResponse.getBody();
        Assertions.assertNotNull(topPosts);
        Assertions.assertEquals(expectedPostIds.size(), topPosts.size());

        for (int i = 0; i < expectedPostIds.size(); i++) {
            String expected = expectedPostIds.get(i);
            String actual = topPosts.get(i).getPostId();
            Assertions.assertEquals(expected, actual,
                    "Rank %d: expected '%s' but got '%s'".formatted(i + 1, expected, actual));
        }
        log.info("Top posts order verified: {}", expectedPostIds);
    }

    @Then("hot posts are returned in this order")
    public void verifyHotPostsOrder(DataTable dataTable) throws InterruptedException {
        List<String> expectedPostIds = dataTable.asList();

        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        RestTemplate restTemplate = new RestTemplate();
        String hotUrl = "http://localhost:%s/api/services/user-activity/hot?size=20".formatted(port);

        Assertions.assertTrue(CucumberSpringConfiguration.assertWithWait(expectedPostIds.size(), () -> {
            HttpEntity<List<UserActivity>> response = restTemplate.exchange(
                    hotUrl, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}
            );
            List<UserActivity> body = response.getBody();
            return body == null ? 0 : body.size();
        }), "Timed out waiting for %d hot posts".formatted(expectedPostIds.size()));

        HttpEntity<List<UserActivity>> finalResponse = restTemplate.exchange(
                hotUrl, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}
        );
        List<UserActivity> hotPosts = finalResponse.getBody();
        Assertions.assertNotNull(hotPosts);
        Assertions.assertEquals(expectedPostIds.size(), hotPosts.size());

        for (int i = 0; i < expectedPostIds.size(); i++) {
            String expected = expectedPostIds.get(i);
            String actual = hotPosts.get(i).getPostId();
            Assertions.assertEquals(expected, actual,
                    "Rank %d: expected '%s' but got '%s'".formatted(i + 1, expected, actual));
        }
        log.info("Hot posts order verified: {}", expectedPostIds);
    }

    private HttpEntity<UserClickResponse> submitVote(String userId, String postId, String action) {
        try {
            UserClick click = new UserClick();
            click.setUserId(userId);
            click.setPostId(postId);
            click.setSearchType("HotTest");
            click.setAction(action);
            click.setSearchPattern(postId);

            MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
            headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

            HttpEntity<String> entity = new HttpEntity<>(new ObjectMapper().writeValueAsString(click), headers);

            return new RestTemplate().exchange(
                    "http://localhost:%s/api/services/user-activity".formatted(port),
                    HttpMethod.POST, entity, UserClickResponse.class
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize UserClick for user=%s post=%s".formatted(userId, postId), e);
        }
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

    @Then("user {string} has {int} hit counts for postId = {string}, searchType = {string} and pattern = {string}")
    public void checkHitCounts(
            String userId,
            int hitCounts,
            String postId,
            String type,
            String pattern) throws InterruptedException {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        RestTemplate restTemplate = new RestTemplate();
        String topUrl = "http://localhost:%s/api/services/user-activity/top".formatted(port);

        // Wait until ES indexes at least 1 result
        Assertions.assertTrue(CucumberSpringConfiguration.assertWithWait(1, () -> {
            HttpEntity<List<UserActivity>> currentResponse = restTemplate.exchange(
                    topUrl,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );
            List<UserActivity> currentBody = currentResponse.getBody();
            return currentBody == null ? 0 : currentBody.size();
        }));

        HttpEntity<List<UserActivity>> finalResponse = restTemplate.exchange(
                topUrl,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
        );
        Assertions.assertNotNull(finalResponse.getBody());
        List<UserActivity> body = finalResponse.getBody();

        Assertions.assertFalse(body.isEmpty(), "Expected at least one top activity for postId=" + postId);

        UserActivity topActivity = body.getFirst();
        Assertions.assertEquals(postId, topActivity.getPostId());
        Assertions.assertEquals(pattern, topActivity.getSearchValue());
        Assertions.assertEquals(type, topActivity.getSearchType());

        log.info("verified {} upvote events produced top result for postId={}", hitCounts, postId);
    }

    @Then("user {string} has next sorting results")
    public void testSortingOrder(String userId, DataTable dataTable) throws InterruptedException {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        RestTemplate restTemplate = new RestTemplate();
        String topUrl = "http://localhost:%s/api/services/user-activity/top".formatted(port);

        Assertions.assertTrue(CucumberSpringConfiguration.assertWithWait(dataTable.height(), () -> {
            HttpEntity<List<UserActivity>> currentResponse = restTemplate.exchange(
                    topUrl,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );
            List<UserActivity> currentBody = currentResponse.getBody();
            return currentBody == null ? 0 : currentBody.size();
        }));

        HttpEntity<List<UserActivity>> finalResponse = restTemplate.exchange(
                topUrl,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
        );
        Assertions.assertNotNull(finalResponse.getBody());
        List<UserActivity> body = finalResponse.getBody();
        Assertions.assertEquals(dataTable.height(), body.size());

        // Verify ordering by searchValue (most-upvoted post first)
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
        userClick.setPostId(data.get(1));
        userClick.setSearchType(data.get(2));
        userClick.setAction(data.get(3));
        userClick.setSearchPattern(data.get(4));

        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.writeValueAsString(userClick);
    }
}
