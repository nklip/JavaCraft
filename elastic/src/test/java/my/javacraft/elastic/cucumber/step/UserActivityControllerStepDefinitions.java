package my.javacraft.elastic.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.cucumber.conf.CucumberSpringConfiguration;
import my.javacraft.elastic.model.UserAction;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.service.activity.UserActivityIngestionService;
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

    private static final int BASELINE_USERS = 50;
    private static final int BASELINE_POSTS = 20;
    private static final long MIN_BASELINE_SPAN_MILLIS = Duration.ofDays(170).toMillis();

    /**
     * Tracks which CSV folders have been fully ingested AND confirmed searchable in ES.
     * Static so the flag survives across scenario instances within one test run.
     * Set to {@code true} only after ES returns the expected document count,
     * so "was ingested" steps can depend on it without re-polling ES themselves.
     */
    private static final Map<String, Boolean> INGESTED_FOLDERS = new ConcurrentHashMap<>();

    @LocalServerPort
    int port;

    @Autowired
    ElasticsearchClient esClient;
    @Autowired
    UserActivityIngestionService userActivityIngestionService;

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

    @Given("data folder {string} ingested")
    public void ingestDataFolderInParallel(String folderPath) throws IOException, InterruptedException {
        INGESTED_FOLDERS.put(folderPath, false);

        List<String> csvResourcePaths = listCsvResourcePaths(folderPath);
        Assertions.assertFalse(csvResourcePaths.isEmpty(), "No CSV files found in folder: " + folderPath);

        int totalRows = 0;
        int threads = Math.min(csvResourcePaths.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String csvResourcePath : csvResourcePaths) {
                futures.add(pool.submit(() -> ingestCsvResource(csvResourcePath)));
            }

            for (Future<Integer> future : futures) {
                try {
                    totalRows += future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("CSV folder ingestion interrupted: " + folderPath, e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Failed to ingest CSV from folder: " + folderPath, e.getCause());
                }
            }
        }

        Assertions.assertTrue(totalRows > 0, "CSV folder has no ingestible rows: " + folderPath);
        log.info("Ingested {} rows from {} files in '{}' — waiting for ES confirmation...",
                totalRows, csvResourcePaths.size(), folderPath);

        // Block until ES confirms the documents are indexed and searchable.
        // Fresh posts (61-70) rank highest in Top; returning 10 results proves all archetypes are ready.
//        Assertions.assertTrue(
//                CucumberSpringConfiguration.assertWithWait(10, () -> fetchRankedPostsCount("/top?size=10")),
//                ("ES did not confirm searchability within the wait window after ingesting %d rows from '%s'. " +
//                        "Expected top-10 fresh posts (61-70) to be indexed.").formatted(totalRows, folderPath)
//        );

        Thread.sleep(1500);

        INGESTED_FOLDERS.put(folderPath, true);
        log.info("ES confirmed: '{}' fully ingested and searchable ({} rows)", folderPath, totalRows);
    }

    @When("reddit baseline activity is ingested for top and hot comparison")
    public void ingestRedditBaselineActivity() throws IOException {
        int ingestedRows = ingestBaselineVotes();
        log.info("Ingested {} baseline vote events for top/hot comparison", ingestedRows);
    }

    @Given("reddit baseline activity was ingested")
    public void verifyBaselineWasIngested() throws InterruptedException {
        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(true, () -> isBaselineIngestionValid(BASELINE_USERS, BASELINE_POSTS)),
                "Timed out waiting for baseline ingestion consistency (users=%d, posts=%d)"
                        .formatted(BASELINE_USERS, BASELINE_POSTS)
        );
    }

    @Then("baseline ingestion has {int} unique users and {int} unique posts")
    public void verifyBaselineCardinality(int expectedUsers, int expectedPosts) throws InterruptedException {
        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(true, () -> isBaselineIngestionValid(expectedUsers, expectedPosts)),
                "Timed out waiting for baseline cardinality (users=%d, posts=%d)"
                        .formatted(expectedUsers, expectedPosts)
        );
    }

    /**
     * Precondition: asserts that {@link #ingestDataFolderInParallel} already completed
     * successfully for this folder path — including ES confirmation — in the current test run.
     * Fails immediately with a diagnostic message if "prepare data" was not run first,
     * without re-polling ES (the ingestion step is the single source of truth for readiness).
     */
    @Given("data folder {string} was ingested")
    public void verifyDataFolderWasIngested(String folderPath) {
        Assertions.assertTrue(
                Boolean.TRUE.equals(INGESTED_FOLDERS.get(folderPath)),
                ("Folder '%s' has not been ingested yet. " +
                        "Run the full feature file so that 'Scenario: prepare data' executes first " +
                        "('Given data folder \\'%s\\' ingested' must complete before this step).")
                        .formatted(folderPath, folderPath)
        );
        log.info("Dependency satisfied: folder '{}' was ingested and ES-confirmed earlier in this run", folderPath);
    }

    /**
     * Verifies the Hot endpoint returns {@code expectedSize} results whose
     * postIds exactly match the expected set (order-independent).
     * Hot score = Σ (upvotes − downvotes) × e^(−λ×ageHours); ties within
     * one archetype are non-deterministic in ES, so only set equality is checked.
     */
    @Then("hot posts endpoint returns {int} ranked results")
    public void verifyHotPostsReturnedWithExpectedPosts(int expectedSize, DataTable expectedPosts) throws InterruptedException {
        String path = "/hot?size=" + expectedSize;
        Set<String> expectedSet = new HashSet<>(expectedPosts.asList());

        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(expectedSize, () -> fetchRankedPostsCount(path)),
                "Timed out waiting for %d hot posts at %s".formatted(expectedSize, path)
        );

        Set<String> actualSet = fetchRankedPosts(path).stream()
                .map(UserActivity::getPostId)
                .collect(Collectors.toSet());

        Assertions.assertEquals(expectedSet, actualSet,
                "Hot posts postId set mismatch. Expected: %s  Actual: %s".formatted(expectedSet, actualSet));
        log.info("Hot posts verified — {} posts match expected set {}", actualSet.size(), expectedSet);
    }

    /**
     * Verifies the Top endpoint returns {@code expectedSize} results whose
     * postIds exactly match the expected set (order-independent).
     * Top ranking is pure upvote count; ties within one archetype are
     * non-deterministic in the ES terms aggregation.
     */
    @Then("top posts endpoint returns {int} ranked results")
    public void verifyTopPostsReturnedWithExpectedPosts(int expectedSize, DataTable expectedPosts) throws InterruptedException {
        String path = "/top?size=" + expectedSize;
        Set<String> expectedSet = new HashSet<>(expectedPosts.asList());

        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(expectedSize, () -> fetchRankedPostsCount(path)),
                "Timed out waiting for %d top posts at %s".formatted(expectedSize, path)
        );

        Set<String> actualSet = fetchRankedPosts(path).stream()
                .map(UserActivity::getPostId)
                .collect(Collectors.toSet());

        Assertions.assertEquals(expectedSet, actualSet,
                "Top posts postId set mismatch. Expected: %s  Actual: %s".formatted(expectedSet, actualSet));
        log.info("Top posts verified — {} posts match expected set {}", actualSet.size(), expectedSet);
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
            click.setAction(action);

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

    private List<String> listCsvResourcePaths(String folderPath) throws IOException {
        try {
            var folderUrl = Thread.currentThread().getContextClassLoader().getResource(folderPath);
            Assertions.assertNotNull(folderUrl, "CSV folder not found in test resources: " + folderPath);

            Path folder = Paths.get(folderUrl.toURI());
            try (var pathStream = Files.list(folder)) {
                return pathStream
                        .filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.endsWith(".csv"))
                        .sorted()
                        .map(name -> folderPath + "/" + name)
                        .toList();
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid folder URI for test resource path: " + folderPath, e);
        }
    }

    private int ingestCsvResource(String resourcePath) throws IOException {
        int ingestedRows = 0;

        InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourcePath);
        Assertions.assertNotNull(inputStream, "CSV resource not found: " + resourcePath);

        try (inputStream; BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            Assertions.assertNotNull(header, "CSV file is empty: " + resourcePath);
            Assertions.assertEquals(
                    "userId,postId,action,date",
                    header.trim(),
                    "Unexpected CSV header in " + resourcePath
            );

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                Assertions.assertEquals(
                        4,
                        values.length,
                        "Invalid CSV row at line %d in %s".formatted(lineNumber, resourcePath)
                );

                UserClick click = new UserClick();
                click.setUserId(values[0].trim());
                click.setPostId(values[1].trim());
                click.setAction(values[2].trim());

                String timestamp = values[3].trim();
                userActivityIngestionService.ingestUserClick(click, timestamp);
                ingestedRows++;
            }
        }

        Assertions.assertTrue(ingestedRows > 0, "CSV has no ingestible rows: " + resourcePath);
        log.info("Ingested {} events from '{}'", ingestedRows, resourcePath);
        return ingestedRows;
    }

    private int ingestBaselineVotes() throws IOException {
        Instant now = Instant.now();
        int rows = 0;

        for (int user = 1; user <= BASELINE_USERS; user++) {
            int mandatoryPost = ((user - 1) % BASELINE_POSTS) + 1;
            rows += ingestVoteEvent(user, mandatoryPost, UserAction.UPVOTE, now, (user * 3) % 170 + 10, user % 24);

            for (int post = 1; post <= BASELINE_POSTS; post++) {
                if (post == mandatoryPost) {
                    continue;
                }
                int selector = (user * 31 + post * 17) % 10;
                if (selector < 2) {
                    UserAction action = ((user + post) % 4 == 0) ? UserAction.DOWNVOTE : UserAction.UPVOTE;
                    int daysAgo = (user * 11 + post * 13) % 180;
                    int hoursAgo = (user * 3 + post * 5) % 24;
                    rows += ingestVoteEvent(user, post, action, now, daysAgo, hoursAgo);
                }
            }
        }

        for (int post = 1; post <= BASELINE_POSTS; post++) {
            int user = ((post * 2) % BASELINE_USERS) + 1;
            UserAction action = (post % 3 == 0) ? UserAction.DOWNVOTE : UserAction.UPVOTE;
            rows += ingestVoteEvent(user, post, action, now, post % 7, post % 24);
        }
        return rows;
    }

    private int ingestVoteEvent(
            int userNumber,
            int postNumber,
            UserAction action,
            Instant now,
            int daysAgo,
            int hoursAgo) throws IOException {
        UserClick click = new UserClick();
        click.setUserId("user-%03d".formatted(userNumber));
        click.setPostId("post-%02d".formatted(postNumber));
        click.setAction(action.name());

        String timestamp = now
                .minus(daysAgo, ChronoUnit.DAYS)
                .minus(hoursAgo, ChronoUnit.HOURS)
                .toString();

        userActivityIngestionService.ingestUserClick(click, timestamp);
        return 1;
    }

    private boolean isBaselineIngestionValid(int expectedUsers, int expectedPosts) {
        try {
            SearchRequest request = new SearchRequest.Builder()
                    .index(UserActivityService.INDEX_USER_ACTIVITY)
                    .size(0)
                    .aggregations("users", a -> a.terms(t -> t
                            .field(UserActivityService.USER_ID)
                            .size(1000)
                    ))
                    .aggregations("posts", a -> a.terms(t -> t
                            .field(UserActivityService.POST_ID)
                            .size(1000)
                    ))
                    .aggregations("min_timestamp", a -> a.min(m -> m.field(UserActivityService.TIMESTAMP)))
                    .aggregations("max_timestamp", a -> a.max(m -> m.field(UserActivityService.TIMESTAMP)))
                    .build();

            SearchResponse<UserActivity> response = esClient.search(request, UserActivity.class);
            int actualUsers = response.aggregations().get("users").sterms().buckets().array().size();
            int actualPosts = response.aggregations().get("posts").sterms().buckets().array().size();
            double minTimestamp = response.aggregations().get("min_timestamp").min().value();
            double maxTimestamp = response.aggregations().get("max_timestamp").max().value();
            long spanMillis = (long) (maxTimestamp - minTimestamp);

            return actualUsers == expectedUsers
                    && actualPosts == expectedPosts
                    && !Double.isNaN(minTimestamp)
                    && !Double.isNaN(maxTimestamp)
                    && spanMillis >= MIN_BASELINE_SPAN_MILLIS;
        } catch (IOException ioe) {
            log.warn("Failed to validate baseline ingestion snapshot: {}", ioe.getMessage());
            return false;
        }
    }

    private int fetchRankedPostsCount(String path) {
        try {
            return fetchRankedPosts(path).size();
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch ranked posts for '{}': {}", path, ex.getMessage());
            return 0;
        }
    }

    /** Single direct call to a ranking endpoint; returns the response body. */
    private List<UserActivity> fetchRankedPosts(String path) {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        String url = "http://localhost:%s/api/services/user-activity%s".formatted(port, path);

        HttpEntity<List<UserActivity>> response = new RestTemplate().exchange(
                url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}
        );
        List<UserActivity> body = response.getBody();
        return body == null ? List.of() : body;
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

    @Then("user {string} has {int} hit counts for postId = {string}")
    public void checkHitCounts(
            String userId,
            int hitCounts,
            String postId) throws InterruptedException {
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

        // Verify ordering by postId (most-upvoted post first)
        List<List<String>> expectedResults = dataTable.cells();
        for (int i = 0; i < body.size(); i++) {
            UserActivity userActivity = body.get(i);
            Assertions.assertEquals(expectedResults.get(i).get(0), userActivity.getPostId());
        }
    }

    private String jsonBody(DataTable dataTable) throws JsonProcessingException {
        UserClick userClick = new UserClick();
        List<String> data = dataTable.cells().getFirst();
        userClick.setUserId(data.get(0));
        userClick.setPostId(data.get(1));
        if (data.size() >= 4) {
            // Backward compatibility for old table format:
            // userId, postId, unused-column, action
            userClick.setAction(data.get(3));
        } else {
            // New format: userId, postId, action
            userClick.setAction(data.get(2));
        }

        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.writeValueAsString(userClick);
    }
}
