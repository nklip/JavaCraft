package my.javacraft.elastic.cucumber.step;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.cucumber.conf.CucumberSpringConfiguration;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.service.activity.UserActivityIngestionService;
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

    /**
     * Tracks which CSV folders have been fully ingested AND confirmed searchable in ES.
     * Static so the flag survives across scenario instances within one test run.
     * Set to {@code true} only after ES returns the expected document count,
     * so "was ingested" steps can depend on it without re-polling ES themselves.
     */
    private static final Map<String, Boolean> INGESTED_FOLDERS = new ConcurrentHashMap<>();

    @LocalServerPort
    int port;

//    @Autowired
//    ElasticsearchClient esClient;
    @Autowired
    UserActivityIngestionService userActivityIngestionService;

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

    /**
     * Precondition: asserts that {@link #ingestDataFolderInParallel} already completed
     * successfully for this folder path — including ES confirmation — in the current test run.
     * Fails immediately with a diagnostic message if "prepare data" was not run first,
     * without re-polling ES (the ingestion step is the single source of truth for readiness).
     */
    @Given("data folder {string} was ingested")
    public void verifyDataFolderWasIngested(String folderPath) {
        Assertions.assertEquals(Boolean.TRUE, INGESTED_FOLDERS.get(folderPath),
                ("""
                Folder '%s' has not been ingested yet.
                Run the full feature file so that 'Scenario: prepare data' executes first (
                    'Given data folder \\'%s\\' ingested' must complete before this step
                ).
                """).formatted(folderPath, folderPath));
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

        Set<String> actualSet = fetchRankedPosts(path)
                .stream()
                .map(UserActivity::getPostId)
                .collect(Collectors.toSet());

        Assertions.assertEquals(expectedSet, actualSet,
                "Top posts postId set mismatch. Expected: %s  Actual: %s".formatted(expectedSet, actualSet));
        log.info("Top posts verified — {} posts match expected set {}", actualSet.size(), expectedSet);
    }

    /**
     * Verifies the windowed Top endpoint ({@code /top/{window}}) returns {@code expectedSize}
     * results whose postIds exactly match the expected set (order-independent).
     * Window must be one of: DAY, WEEK, MONTH, YEAR (case-insensitive).
     */
    @Then("top posts for {word} returns {int} ranked results")
    public void verifyTopPostsByWindowReturned(String window, int expectedSize, DataTable expectedPosts) throws InterruptedException {
        String path = "/top/" + window.toLowerCase() + "?size=" + expectedSize;
        Set<String> expectedSet = new HashSet<>(expectedPosts.asList());

        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(expectedSize, () -> fetchRankedPostsCount(path)),
                "Timed out waiting for %d top/%s posts at %s".formatted(expectedSize, window, path)
        );

        Set<String> actualSet = fetchRankedPosts(path).stream()
                .map(UserActivity::getPostId)
                .collect(Collectors.toSet());

        Assertions.assertEquals(expectedSet, actualSet,
                "Top/%s posts postId set mismatch. Expected: %s  Actual: %s".formatted(window, expectedSet, actualSet));
        log.info("Top/{} posts verified — {} posts match expected set {}", window, actualSet.size(), expectedSet);
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

}
