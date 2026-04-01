package dev.nklip.javacraft.elastic.data.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Slf4j
@SuppressWarnings("SameParameterValue")
public final class BooksDownloader implements JsonDownloader<BooksDownloader.BookRecord> {

    private static final String POPULAR_BOOKS_URL = "https://gutendex.com/books";

    private static final int TARGET_BOOKS_COUNT = 1000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private static final RestTemplate REST_TEMPLATE = createRestTemplate();

    private static final Path DEFAULT_OUTPUT_FILE = Path.of(
            "elastic", "elastic-testing",
            "src", "test", "resources", "data", "json", "books.json"
    );

    private BooksDownloader() {
    }

    public static void main(String[] args) throws IOException {
        log.info("Starting BooksDownloader...");
        new BooksDownloader().run(args);
    }

    @Override
    public String datasetName() {
        return "books";
    }

    @Override
    public Path defaultOutputFile() {
        return DEFAULT_OUTPUT_FILE;
    }

    @Override
    public List<BookRecord> downloadRecords() throws IOException {
        log.info("Fetching books...");
        return downloadPopularBooks(TARGET_BOOKS_COUNT);
    }

    private static List<BookRecord> downloadPopularBooks(int targetBooksCount) throws IOException {
        List<BookRecord> records = new ArrayList<>(targetBooksCount);
        Set<String> deduplicationKeys = new LinkedHashSet<>();
        String nextPageUrl = POPULAR_BOOKS_URL;

        while (nextPageUrl != null && records.size() < targetBooksCount) {
            GutendexResponse page = fetchPage(nextPageUrl);
            List<GutendexBook> books = safeList(page.results()).stream()
                    .sorted(Comparator.comparingLong(BooksDownloader::safeDownloadCount).reversed())
                    .toList();
            if (books.isEmpty()) {
                break;
            }

            for (GutendexBook book : books) {
                if (records.size() >= targetBooksCount) {
                    break;
                }
                Optional<BookRecord> maybeRecord = toBookRecord(book, records.size() + 1);
                if (maybeRecord.isEmpty()) {
                    continue;
                }

                BookRecord record = maybeRecord.get();
                if (!deduplicationKeys.add(deduplicationKey(record))) {
                    continue;
                }

                records.add(record);
                log.info(
                        "Downloaded book {}/{}: '{}' by '{}'",
                        record.ranking(),
                        targetBooksCount,
                        record.name(),
                        record.author()
                );
            }

            nextPageUrl = page.next();
        }

        if (records.size() < targetBooksCount) {
            log.warn(
                    "Requested {} popular books but downloaded {}. Source pages were exhausted.",
                    targetBooksCount,
                    records.size()
            );
        }

        return records;
    }

    private static GutendexResponse fetchPage(String url) throws IOException {
        ResponseEntity<byte[]> response = REST_TEMPLATE.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(null, defaultHeaders()),
                byte[].class
        );
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IOException("Unable to fetch books from '%s': %s".formatted(url, response.getStatusCode()));
        }

        String body = decodeResponseBody(response.getBody());
        if (body.isBlank()) {
            throw new IOException("Received empty response body from '%s'".formatted(url));
        }

        return SHARED_OBJECT_MAPPER.readValue(body, GutendexResponse.class);
    }

    private static Optional<BookRecord> toBookRecord(GutendexBook book, int ranking) {
        String title = sanitize(book.title());
        if (title.isEmpty()) {
            return Optional.empty();
        }

        String author = firstAuthorName(book);
        if (author.isEmpty()) {
            author = "Unknown";
        }

        return Optional.of(new BookRecord(
                author,
                extractGenres(book),
                title,
                ranking,
                extractReleaseYear(book),
                extractSynopsis(book)
        ));
    }

    private static String firstAuthorName(GutendexBook book) {
        return safeList(book.authors()).stream()
                .map(GutendexAuthor::name)
                .map(BooksDownloader::sanitize)
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElse("");
    }

    private static int extractReleaseYear(GutendexBook book) {
        return findYearInText(book.title())
                .or(() -> safeList(book.subjects()).stream()
                        .map(BooksDownloader::findYearInText)
                        .flatMap(Optional::stream)
                        .findFirst())
                .orElse(0);
    }

    private static List<String> extractGenres(GutendexBook book) {
        List<String> bookshelves = sanitizeAndLimit(book.bookshelves());
        if (!bookshelves.isEmpty()) {
            return bookshelves;
        }
        List<String> subjects = sanitizeAndLimit(book.subjects());
        if (!subjects.isEmpty()) {
            return subjects;
        }
        return List.of("Unknown");
    }

    private static List<String> sanitizeAndLimit(List<String> values) {
        return safeList(values).stream()
                .map(BooksDownloader::sanitize)
                .filter(value -> !value.isEmpty())
                .limit(5)
                .toList();
    }

    private static String extractSynopsis(GutendexBook book) {
        return safeList(book.summaries()).stream()
                .map(BooksDownloader::sanitize)
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElseGet(() -> safeList(book.subjects()).stream()
                        .map(BooksDownloader::sanitize)
                        .filter(value -> !value.isEmpty())
                        .findFirst()
                        .orElse("Synopsis not available."));
    }

    private static String deduplicationKey(BookRecord record) {
        return "%s|%s".formatted(
                sanitize(record.name()).toLowerCase(Locale.ROOT),
                sanitize(record.author()).toLowerCase(Locale.ROOT)
        );
    }

    private static long safeDownloadCount(GutendexBook book) {
        Long count = book.downloadCount();
        return (count == null || count < 0) ? 0L : count;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static Optional<Integer> findYearInText(String value) {
        String sanitized = sanitize(value);
        if (sanitized.length() < 4) {
            return Optional.empty();
        }

        for (int index = 0; index <= sanitized.length() - 4; index++) {
            String maybeYear = sanitized.substring(index, index + 4);
            if (!maybeYear.chars().allMatch(Character::isDigit)) {
                continue;
            }
            int year = Integer.parseInt(maybeYear);
            if (year >= 1000 && year <= 2999) {
                return Optional.of(year);
            }
        }
        return Optional.empty();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static HttpHeaders defaultHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");
        return headers;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(requestFactory);
    }

    private static String decodeResponseBody(byte[] responseBody) throws IOException {
        if (responseBody == null || responseBody.length == 0) {
            return "";
        }

        if (isGzip(responseBody)) {
            try (InputStream gzipStream = new GZIPInputStream(new ByteArrayInputStream(responseBody))) {
                return new String(gzipStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(responseBody, StandardCharsets.UTF_8);
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2
                && (bytes[0] == (byte) 0x1f)
                && (bytes[1] == (byte) 0x8b);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GutendexResponse(List<GutendexBook> results, String next) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GutendexBook(
            String title,
            List<GutendexAuthor> authors,
            List<String> subjects,
            List<String> bookshelves,
            List<String> summaries,
            @JsonProperty("download_count")
            Long downloadCount
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GutendexAuthor(String name) {}

    public record BookRecord(
            String author,
            List<String> genres,
            String name,
            Integer ranking,
            @JsonProperty("release_year")
            Integer releaseYear,
            String synopsis
    ) {}
}
