package my.javacraft.elastic.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.cucumber.conf.CucumberSpringConfiguration;
import my.javacraft.elastic.cucumber.helper.generator.EventCsvSupport;
import my.javacraft.elastic.cucumber.helper.generator.EventGenerator;
import my.javacraft.elastic.cucumber.helper.generator.impl.BestEvents;
import my.javacraft.elastic.cucumber.helper.generator.impl.HotEvents;
import my.javacraft.elastic.cucumber.helper.generator.impl.NewEvents;
import my.javacraft.elastic.cucumber.helper.generator.impl.RisingEvents;
import my.javacraft.elastic.cucumber.helper.generator.impl.TopEvents;
import my.javacraft.elastic.model.PostPreview;
import my.javacraft.elastic.model.VoteRequest;
import my.javacraft.elastic.service.activity.VoteService;
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
public class PostRankingControllerStepDefinitions {

    /** Minimum number of documents expected in the index after full CSV ingestion (5 files × 1 000 rows). */
    private static final long MIN_CSV_DOCUMENTS = 5_000L;

    /** Number of CSV files expected in the generated data folder (one per EventGenerator). */
    private static final int EXPECTED_CSV_FILES = 5;

    /**
     * Maximum age of cached CSV files before they must be regenerated.
     * <p>
     * Driven by the tightest time window in the test suite: {@code Top posts for DAY} filters
     * {@code timestamp >= now − 24 h}. HotEvents timestamps are spread within the last 60 minutes
     * of generation, so they fall outside the DAY window once the files are ~24 h old.
     * Using 22 h leaves a 2-hour safety buffer (60 min for HotEvents spread + ~60 min for test
     * execution time or clock drift).
     */
    private static final long MAX_CSV_AGE_HOURS = 22;

    /**
     * Filesystem path where {@link #createDataFolderInTmpDirectory} wrote the generated CSV files.
     * {@code null} when CSVs are expected to be read from the test-resources classpath instead.
     * Static so that the value set in "prepare data" survives across Cucumber scenario instances.
     */
    private static volatile Path tmpCsvDir;

    @LocalServerPort
    int port;

    @Autowired
    ElasticsearchClient esClient;
    @Autowired
    VoteService voteService;

    /**
     * Generates all 5 CSV fixture files into a fixed temporary directory and stores the path in
     * {@link #tmpCsvDir} for the subsequent ingestion step to consume.
     * <p>
     * The directory is always {@code $TMPDIR/javacraft-events/<relPath>} (a deterministic path),
     * so repeated runs within the same OS session reuse the same location and leave no orphaned dirs.
     * Files survive until the next OS reboot (macOS clears {@code /tmp} on restart).
     * <p>
     * Idempotent with age check: if the directory already contains all {@value #EXPECTED_CSV_FILES}
     * CSV files AND they are younger than {@value #MAX_CSV_AGE_HOURS} hours, generation is skipped.
     * Files older than that are regenerated because time-windowed scenarios (e.g. {@code Top posts
     * for DAY}) depend on HotEvents timestamps being inside the last-24-hours window.
     * <p>
     * The generators are pointed at the temp dir via the
     * {@value EventCsvSupport#OUTPUT_DIRECTORY_PROPERTY} system property, which is cleared
     * immediately after generation so it does not bleed into other tests.
     */
    @Given("data folder {string} created in tmp directory")
    public void createDataFolderInTmpDirectory(String relPath) throws IOException {
        // e.g. /var/folders/.../T/javacraft-events/data/csv  (macOS)
        //   or /tmp/javacraft-events/data/csv                (Linux)
        tmpCsvDir = Path.of(System.getProperty("java.io.tmpdir"), "javacraft-events", relPath);
        Files.createDirectories(tmpCsvDir);

        if (csvFilesAlreadyExistsInValidState()) {
            log.info("CSV files still fresh in '{}', skipping generation", tmpCsvDir);
            return;
        }
        log.info("CSV files missing or stale in '{}', regenerating", tmpCsvDir);

        System.setProperty(EventCsvSupport.OUTPUT_DIRECTORY_PROPERTY, tmpCsvDir.toString());
        try {
            List<EventGenerator> generators = List.of(
                    new BestEvents(), new HotEvents(), new NewEvents(), new RisingEvents(), new TopEvents()
            );
            for (EventGenerator generator : generators) {
                generator.generateEventsInCsv();
            }
            log.info("Generated {} CSV files in '{}'", generators.size(), tmpCsvDir);
        } finally {
            System.clearProperty(EventCsvSupport.OUTPUT_DIRECTORY_PROPERTY);
        }
    }

    /**
     * Returns {@code true} when {@link #tmpCsvDir} exists, holds all {@value #EXPECTED_CSV_FILES}
     * CSV files, and the newest file is younger than {@value #MAX_CSV_AGE_HOURS} hours.
     * <p>
     * The age check is necessary because time-windowed scenarios (e.g. {@code Top posts for DAY})
     * depend on HotEvents timestamps being inside the {@code now − 24 h} window. Files older than
     * {@value #MAX_CSV_AGE_HOURS} hours would cause those scenarios to return 0 results for
     * HotEvents and fail the assertion.
     */
    private static boolean csvFilesAlreadyExistsInValidState() throws IOException {
        if (tmpCsvDir == null || !Files.isDirectory(tmpCsvDir)) {
            return false;
        }
        List<Path> csvFiles;
        try (var stream = Files.list(tmpCsvDir)) {
            csvFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .toList();
        }
        if (csvFiles.size() < EXPECTED_CSV_FILES) {
            return false;
        }
        Instant cutoff = Instant.now().minus(MAX_CSV_AGE_HOURS, ChronoUnit.HOURS);
        return csvFiles.stream()
                .map(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toInstant();
                    } catch (IOException e) {
                        return Instant.EPOCH;
                    }
                })
                .min(Comparator.naturalOrder())
                .map(oldest -> oldest.isAfter(cutoff))
                .orElse(false);
    }

    /**
     * Ingests all CSV files from either the temp directory (when
     * {@link #createDataFolderInTmpDirectory} ran first) or the test-resources classpath.
     * Files are ingested in parallel — one thread per file, bounded by available CPUs.
     */
    @Given("data folder {string} ingested")
    public void ingestDataFolderInParallel(String folderPath) throws IOException {
        List<Path> csvFiles = listCsvFiles(folderPath);
        Assertions.assertFalse(csvFiles.isEmpty(), "No CSV files found in folder: " + folderPath);

        int totalRows = 0;
        int threads = Math.min(csvFiles.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Integer>> futures = csvFiles.stream()
                    .map(path -> pool.submit(() -> ingestCsvFile(path)))
                    .toList();

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
        log.info("Ingested {} rows from {} files in '{}'", totalRows, csvFiles.size(), folderPath);
    }

    /**
     * Precondition: polls ES until the 'user-activity' index contains at least
     * {@value #MIN_CSV_DOCUMENTS} documents, confirming that the CSV data ingested
     * by {@link #ingestDataFolderInParallel} is fully visible to search queries.
     * Fails with a clear diagnostic message if the count is not reached within the wait window.
     */
    @Given("data folder {string} was ingested")
    public void verifyDataFolderWasIngested(String folderPath) throws InterruptedException {
        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(true, () -> countIndexDocuments() >= MIN_CSV_DOCUMENTS),
                ("ES index '%s' does not contain the expected minimum of %d documents after ingesting '%s'. " +
                        "Actual count: %d. Ensure 'Scenario: prepare data' ran first.")
                        .formatted(Constants.INDEX_USER_VOTE, MIN_CSV_DOCUMENTS, folderPath, countIndexDocuments())
        );
        log.info("ES confirmed: index '{}' has ≥{} documents — folder '{}' is ready",
                Constants.INDEX_USER_VOTE, MIN_CSV_DOCUMENTS, folderPath);
    }

    /** Returns the current document count in the user-activity index, or 0 on any error. */
    private long countIndexDocuments() {
        try {
            return esClient.count(r -> r.index(Constants.INDEX_USER_VOTE)).count();
        } catch (IOException e) {
            log.warn("Failed to count documents in index '{}': {}", Constants.INDEX_USER_VOTE, e.getMessage());
            return 0L;
        }
    }

    /**
     * Verifies the Hot endpoint returns {@code expectedSize} results whose
     * postIds and karma values exactly match the expected table (order-independent).
     * Hot score = Σ (upvotes − downvotes) × e^(−λ×ageHours); ties within
     * one archetype are non-deterministic in ES, so only map equality is checked.
     */
    @Then("hot posts endpoint returns {int} ranked results")
    public void verifyHotPostsReturnedWithExpectedPosts(int expectedSize, DataTable expectedPosts) throws InterruptedException {
        String path = "/hot?size=" + expectedSize;
        verifyRankedPosts(path, expectedSize, expectedPosts, "hot");
    }

    /**
     * Verifies the Top endpoint returns {@code expectedSize} results whose
     * postIds and karma values exactly match the expected table (order-independent).
     * Top ranking is pure upvote count; ties within one archetype are
     * non-deterministic in the ES terms aggregation.
     */
    @Then("top posts endpoint returns {int} ranked results")
    public void verifyTopPostsReturnedWithExpectedPosts(int expectedSize, DataTable expectedPosts) throws InterruptedException {
        String path = "/top?size=" + expectedSize;
        verifyRankedPosts(path, expectedSize, expectedPosts, "top");
    }

    /**
     * Verifies the windowed Top endpoint ({@code /top/{window}}) returns {@code expectedSize}
     * results whose postIds and karma values exactly match the expected table (order-independent).
     * Window must be one of: DAY, WEEK, MONTH, YEAR (case-insensitive).
     */
    @Then("top posts for {word} returns {int} ranked results")
    public void verifyTopPostsByWindowReturned(String window, int expectedSize, DataTable expectedPosts) throws InterruptedException {
        String path = "/top/" + window.toLowerCase() + "?size=" + expectedSize;
        verifyRankedPosts(path, expectedSize, expectedPosts, "top/" + window);
    }

    /*
     * Shared assertion logic for all three ranking endpoints.
     */
    private void verifyRankedPosts(String path, int expectedSize, DataTable expectedPosts, String label)
            throws InterruptedException {

        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(expectedSize, () -> fetchRankedPostsCount(path)),
                "Timed out waiting for %d %s posts at %s".formatted(expectedSize, label, path)
        );

        List<PostPreview> actualPosts = fetchRankedPosts(path);

        for (int i = 0; i < actualPosts.size(); i++) {
            PostPreview actualPost = actualPosts.get(i);
            // first row is column names
            List<String> expectedRow = expectedPosts.row(i + 1);

            // karma is the 2nd column
            Assertions.assertEquals(Long.parseLong(expectedRow.get(1)), actualPost.getKarma());
        }
    }

    /**
     * Returns the CSV files to ingest. When {@link #tmpCsvDir} is set (i.e.
     * {@link #createDataFolderInTmpDirectory} ran), files are listed from the filesystem.
     * Otherwise, falls back to listing from the test-resources classpath.
     */
    private List<Path> listCsvFiles(String folderPath) throws IOException {
        if (tmpCsvDir != null) {
            try (var stream = Files.list(tmpCsvDir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".csv"))
                        .sorted()
                        .toList();
            }
        }
        return listCsvResourcePaths(folderPath).stream()
                .map(resourcePath -> {
                    var url = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
                    Assertions.assertNotNull(url, "CSV resource not found: " + resourcePath);
                    try {
                        return Paths.get(url.toURI());
                    } catch (URISyntaxException e) {
                        throw new IllegalStateException("Invalid URI for classpath resource: " + resourcePath, e);
                    }
                })
                .toList();
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

    private int ingestCsvFile(Path csvFile) throws IOException {
        try (InputStream inputStream = Files.newInputStream(csvFile)) {
            return ingestCsvInputStream(inputStream, csvFile.toString());
        }
    }

    private int ingestCsvInputStream(InputStream inputStream, String sourceName) throws IOException {
        int ingestedRows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            Assertions.assertNotNull(header, "CSV file is empty: " + sourceName);
            Assertions.assertEquals(
                    "userId,postId,action,date",
                    header.trim(),
                    "Unexpected CSV header in " + sourceName
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
                        "Invalid CSV row at line %d in %s".formatted(lineNumber, sourceName)
                );

                VoteRequest click = new VoteRequest();
                click.setUserId(values[0].trim());
                click.setPostId(values[1].trim());
                click.setAction(values[2].trim());

                String timestamp = values[3].trim();
                voteService.ingestUserEvent(click, timestamp);
                ingestedRows++;
            }
        }

        Assertions.assertTrue(ingestedRows > 0, "CSV has no ingestible rows: " + sourceName);
        log.info("Ingested {} events from '{}'", ingestedRows, sourceName);
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
    private List<PostPreview> fetchRankedPosts(String path) {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        String url = "http://localhost:%s/api/services/posts/ranking%s".formatted(port, path);

        HttpEntity<List<PostPreview>> response = new RestTemplate().exchange(
                url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}
        );
        List<PostPreview> body = response.getBody();
        return body == null ? List.of() : body;
    }

}
