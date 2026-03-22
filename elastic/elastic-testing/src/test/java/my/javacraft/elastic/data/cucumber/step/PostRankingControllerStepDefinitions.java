package my.javacraft.elastic.data.cucumber.step;

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
import my.javacraft.elastic.app.config.ElasticsearchConstants;
import my.javacraft.elastic.data.csv.CsvSupport;
import my.javacraft.elastic.data.csv.VoteGenerator;
import my.javacraft.elastic.data.csv.impl.*;
import my.javacraft.elastic.data.cucumber.conf.CucumberSpringConfiguration;

import my.javacraft.elastic.api.model.Post;
import my.javacraft.elastic.api.model.VoteRequest;
import my.javacraft.elastic.app.service.VoteService;
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

    /** Minimum number of vote documents expected in user-votes index after full CSV ingestion (5 files × 1 000 rows). */
    private static final long MIN_CSV_DOCUMENTS = 5_000L;

    /** Minimum number of post documents expected in posts index after full CSV ingestion (5 generators × 10 posts). */
    private static final long MIN_POSTS_DOCUMENTS = 50L;

    /** Number of CSV files expected in the generated data folder (5 events + 5 posts, one pair per VoteGenerator). */
    private static final int EXPECTED_CSV_FILES = 10;

    /**
     * Maximum age of cached CSV files before they must be regenerated.
     * <p>
     * Driven by the tightest time window in the test suite: {@code Top posts for DAY} filters
     * {@code timestamp >= now − 24 h}. HotVotesGenerator timestamps are spread within the last 60 minutes
     * of generation, so they fall outside the DAY window once the files are ~24 h old.
     * Using 22 h leaves a 2-hour safety buffer (60 min for HotVotesGenerator spread + ~60 min for test
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
     * for DAY}) depend on HotVotesGenerator timestamps being inside the last-24-hours window.
     * <p>
     * The generators are pointed at the temp dir via the
     * {@value CsvSupport#OUTPUT_DIRECTORY_PROPERTY} system property, which is cleared
     * immediately after generation so it does not bleed into other tests.
     */
    @Given("data folder {string} created in tmp directory")
    public void createDataFolderInTmpDirectory(String relPath) throws IOException {
        // e.g. /var/folders/.../T/javacraft-events/data/csv  (macOS)
        //   or /tmp/javacraft-events/data/csv                (Linux)
        tmpCsvDir = Path.of(System.getProperty("java.io.tmpdir"), "javacraft-events", relPath);
        Files.createDirectories(tmpCsvDir);

        if (csvFilesAlreadyExistsInValidState() && false) {
            log.info("CSV files still fresh in '{}', skipping generation", tmpCsvDir);
            return;
        }
        log.info("CSV files missing or stale in '{}', regenerating", tmpCsvDir);

        System.setProperty(CsvSupport.OUTPUT_DIRECTORY_PROPERTY, tmpCsvDir.toString());
        try {
            List<VoteGenerator> generators = List.of(
                    new BestVotesGenerator(),
                    new HotVotesGenerator(),
                    new NewVotesGenerator(),
                    new RisingVotesGenerator(),
                    new TopVotesGenerator()
            );
            for (VoteGenerator generator : generators) {
                generator.generatePostVotesInCsv();
            }
            log.info("Generated {} CSV files in '{}'", generators.size(), tmpCsvDir);
        } finally {
            System.clearProperty(CsvSupport.OUTPUT_DIRECTORY_PROPERTY);
        }
    }

    /**
     * Returns {@code true} when {@link #tmpCsvDir} exists, holds all {@value #EXPECTED_CSV_FILES}
     * CSV files, and the newest file is younger than {@value #MAX_CSV_AGE_HOURS} hours.
     * <p>
     * The age check is necessary because time-windowed scenarios (e.g. {@code Top posts for DAY})
     * depend on HotVotesGenerator timestamps being inside the {@code now − 24 h} window. Files older than
     * {@value #MAX_CSV_AGE_HOURS} hours would cause those scenarios to return 0 results for
     * HotVotesGenerator and fail the assertion.
     */
    private static boolean csvFilesAlreadyExistsInValidState() throws IOException {
        if (tmpCsvDir == null || !Files.isDirectory(tmpCsvDir)) {
            return false;
        }
        List<Path> csvFiles;
        try (var stream = Files.walk(tmpCsvDir)) {
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
     * Ingests all CSV files in two ordered phases:
     * <ol>
     *   <li>{@code posts/posts-*.csv} — creates post documents with {@code karma = 0}</li>
     *   <li>{@code votes/votes-*.csv} — processes votes, which increment/decrement post karma</li>
     * </ol>
     * Posts must be fully indexed before votes so that {@code PostService#updateScores}
     * finds an existing document on every vote.
     * Within each phase files are ingested in parallel.
     */
    @Given("data folder {string} ingested")
    public void ingestDataFolderInParallel(String folderPath) throws IOException {
        List<Path> csvFiles = listCsvFiles(folderPath);
        Assertions.assertFalse(csvFiles.isEmpty(), "No CSV files found in folder: " + folderPath);

        List<Path> postsCsvs = csvFiles.stream().filter(p -> p.getFileName().toString().startsWith("posts-")).toList();
        List<Path> votesCsvs = csvFiles.stream().filter(p -> p.getFileName().toString().startsWith("votes-")).toList();

        int totalRows = 0;
        totalRows += ingestPhase(postsCsvs, folderPath, "posts");
        totalRows += ingestPhase(votesCsvs, folderPath, "votes");

        // Force a refresh so that all karma updates written by updateScores() during
        // votes ingestion are immediately visible in subsequent search queries.
        // Without this, ES may still serve the pre-update karma=0 values from its
        // segment cache until the automatic 1-second refresh cycle kicks in.
        esClient.indices().refresh(r -> r.index(ElasticsearchConstants.INDEX_POSTS));
        log.info("posts index refreshed after vote ingestion");

        Assertions.assertTrue(totalRows > 0, "CSV folder has no ingestible rows: " + folderPath);
        log.info("Ingested {} rows from {} files in '{}'", totalRows, csvFiles.size(), folderPath);
    }

    private int ingestPhase(List<Path> files, String folderPath, String label) {
        if (files.isEmpty()) return 0;
        int totalRows = 0;
        int threads = Math.min(files.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Integer>> futures = files.stream()
                    .map(path -> pool.submit(() -> ingestCsvFile(path)))
                    .toList();
            for (Future<Integer> future : futures) {
                try {
                    totalRows += future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("CSV folder ingestion interrupted (" + label + "): " + folderPath, e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Failed to ingest CSV from folder (" + label + "): " + folderPath, e.getCause());
                }
            }
        }
        log.info("Phase '{}': ingested {} rows from {} files", label, totalRows, files.size());
        return totalRows;
    }

    /**
     * Precondition: polls ES until both the 'user-votes' index (≥{@value #MIN_CSV_DOCUMENTS} docs)
     * and the 'posts' index (≥{@value #MIN_POSTS_DOCUMENTS} docs) are fully populated.
     * Fails with a clear diagnostic message if either count is not reached within the wait window.
     */
    @Given("data folder {string} was ingested")
    public void verifyDataFolderWasIngested(String folderPath) throws InterruptedException {
        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(true, () -> countVoteDocuments() >= MIN_CSV_DOCUMENTS),
                ("ES index '%s' does not contain the expected minimum of %d documents after ingesting '%s'. " +
                        "Actual count: %d. Ensure 'Scenario: prepare data' ran first.")
                        .formatted(ElasticsearchConstants.INDEX_USER_VOTES, MIN_CSV_DOCUMENTS, folderPath, countVoteDocuments())
        );
        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(true, () -> countPostDocuments() >= MIN_POSTS_DOCUMENTS),
                ("ES index '%s' does not contain the expected minimum of %d documents after ingesting '%s'. " +
                        "Actual count: %d. Ensure 'Scenario: prepare data' ran first.")
                        .formatted(ElasticsearchConstants.INDEX_POSTS, MIN_POSTS_DOCUMENTS, folderPath, countPostDocuments())
        );
        log.info("ES confirmed: '{}' ≥{} docs, '{}' ≥{} docs — folder '{}' is ready",
                ElasticsearchConstants.INDEX_USER_VOTES, MIN_CSV_DOCUMENTS,
                ElasticsearchConstants.INDEX_POSTS, MIN_POSTS_DOCUMENTS,
                folderPath);
    }

    private long countVoteDocuments() {
        try {
            return esClient.count(r -> r.index(ElasticsearchConstants.INDEX_USER_VOTES)).count();
        } catch (IOException e) {
            log.warn("Failed to count documents in index '{}': {}", ElasticsearchConstants.INDEX_USER_VOTES, e.getMessage());
            return 0L;
        }
    }

    private long countPostDocuments() {
        try {
            return esClient.count(r -> r.index(ElasticsearchConstants.INDEX_POSTS)).count();
        } catch (IOException e) {
            log.warn("Failed to count documents in index '{}': {}", ElasticsearchConstants.INDEX_POSTS, e.getMessage());
            return 0L;
        }
    }

    /**
     * Verifies the New endpoint returns {@code expectedSize} results in reverse-chronological order.
     * Submission time is approximated as min(timestamp). The expected table must list posts
     * in submission-time DESC order (most recently submitted first).
     */
    @Then("new posts endpoint returns {int} ranked results")
    public void verifyNewPostsReturned(int expectedSize, DataTable expectedPosts) throws InterruptedException {
        String path = "/new?size=" + expectedSize;
        verifyRankedPosts(path, expectedSize, expectedPosts, "new");
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
     * Verifies the Rising endpoint returns {@code expectedSize} posts from the last 6 hours
     * and that results are sorted by risingScore DESC (postId ASC tie-break).
     */
    @Then("rising posts endpoint returns {int} ranked results")
    public void verifyRisingPostsReturned(int expectedSize, DataTable expectedPosts) throws InterruptedException {
        String path = "/rising?size=" + expectedSize;

        verifyRankedPosts(path, expectedSize, expectedPosts, "rising");
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

    /**
     * Verifies the Best endpoint returns {@code expectedSize} results whose karma values
     * match the expected table in order. Best is sorted by Wilson score lower bound (95 %
     * confidence interval on upvote ratio) — posts with a reliably high upvote percentage
     * and a statistically significant sample size rank highest.
     */
    @Then("best posts endpoint returns {int} ranked results")
    public void verifyBestPostsReturned(int expectedSize, DataTable expectedPosts) throws InterruptedException {
        String path = "/best?size=" + expectedSize;
        verifyRankedPosts(path, expectedSize, expectedPosts, "best");
    }

    /*
     * Shared assertion logic for table-driven ranking endpoints.
     */
    private void verifyRankedPosts(String path, int expectedSize, DataTable expectedPosts, String label)
            throws InterruptedException {

        Assertions.assertTrue(
                CucumberSpringConfiguration.assertWithWait(expectedSize, () -> fetchRankedPostsCount(path)),
                "Timed out waiting for %d %s posts at %s".formatted(expectedSize, label, path)
        );

        List<Post> actualPosts = fetchRankedPosts(path);

        for (int i = 0; i < actualPosts.size(); i++) {
            Post actualPost = actualPosts.get(i);
            // first row is column names
            List<String> expectedRow = expectedPosts.row(i + 1);

            Assertions.assertEquals(
                    expectedRow.get(0).trim(),
                    actualPost.postId(),
                    "unexpected postId at position " + i
            );

            // karma is the 2nd column
            Assertions.assertEquals(
                    Long.parseLong(expectedRow.get(1).trim()),
                    actualPost.karma(),
                    "postId = " + actualPost.postId()
            );
        }
    }

    /**
     * Returns the CSV files to ingest. When {@link #tmpCsvDir} is set (i.e.
     * {@link #createDataFolderInTmpDirectory} ran), files are listed from the filesystem.
     * Otherwise, falls back to listing from the test-resources classpath.
     */
    private List<Path> listCsvFiles(String folderPath) throws IOException {
        if (tmpCsvDir != null) {
            try (var stream = Files.walk(tmpCsvDir)) {
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
            try (var pathStream = Files.walk(folder)) {
                return pathStream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".csv"))
                        .sorted()
                        .map(p -> folderPath + "/" + folder.relativize(p).toString().replace('\\', '/'))
                        .toList();
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid folder URI for test resource path: " + folderPath, e);
        }
    }

    /**
     * Routes ingestion based on file name prefix:
     * <ul>
     *   <li>{@code posts-*.csv}  → {@link #ingestPostsCsvFile} → 'posts' index via {@code esClient.index()}</li>
     *   <li>{@code events-*.csv} → {@link #ingestEventsCsvInputStream} → 'user-votes' index via {@link VoteService}</li>
     * </ul>
     */
    private int ingestCsvFile(Path csvFile) throws IOException {
        String fileName = csvFile.getFileName().toString();
        if (fileName.startsWith("posts-")) {
            return ingestPostsCsvFile(csvFile);
        }
        try (InputStream inputStream = Files.newInputStream(csvFile)) {
            return ingestEventsCsvInputStream(inputStream, csvFile.toString());
        }
    }

    /**
     * Ingests historical post fixtures directly into Elasticsearch, bypassing
     * {@link my.javacraft.elastic.app.service.PostService#submitPost} which generates
     * server-side IDs and timestamps.
     * <p>
     * Using {@code esClient.index()} rather than the production service keeps the
     * production API clean (pure insert, server-generated ID/timestamp) while still
     * allowing the test harness to seed posts with deterministic IDs and historical
     * {@code createdAt} values needed for time-windowed ranking scenarios.
     */
    private int ingestPostsCsvFile(Path csvFile) throws IOException {
        int ingestedRows = 0;
        try (var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(csvFile), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            Assertions.assertNotNull(header, "CSV file is empty: " + csvFile);
            Assertions.assertEquals("postId,createdAt,author", header.trim(),
                    "Unexpected CSV header in " + csvFile);

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                Assertions.assertEquals(3, values.length,
                        "Invalid CSV row at line %d in %s".formatted(lineNumber, csvFile));

                // CSV columns: postId, createdAt, author
                String postId    = values[0].trim();
                String createdAt = values[1].trim();
                String author    = values[2].trim();
                long epochSec    = Instant.parse(createdAt).getEpochSecond();
                double hotScore  = (epochSec - 1_134_028_003L) / 45_000.0;
                Post post = new Post(postId, author, createdAt, 0L, 0L, hotScore, 0.0, 0.0);
                esClient.index(i -> i
                        .index(ElasticsearchConstants.INDEX_POSTS)
                        .id(postId)
                        .document(post)
                );
                ingestedRows++;
            }
        }
        Assertions.assertTrue(ingestedRows > 0, "Posts CSV has no ingestible rows: " + csvFile);
        log.info("Ingested {} posts from '{}'", ingestedRows, csvFile);
        return ingestedRows;
    }

    private int ingestEventsCsvInputStream(InputStream inputStream, String sourceName) throws IOException {
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
                voteService.processVoteRequest(click, timestamp);
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
    private List<Post> fetchRankedPosts(String path) {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        String url = "http://localhost:%s/api/services/posts/ranking%s".formatted(port, path);

        HttpEntity<List<Post>> response = new RestTemplate().exchange(
                url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}
        );
        List<Post> body = response.getBody();
        return body == null ? List.of() : body;
    }

}
