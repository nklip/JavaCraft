package my.javacraft.elastic.data.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Slf4j
public final class BooksDownloader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RestTemplate REST_TEMPLATE = createRestTemplate();
    private static final String POPULAR_BOOKS_URL = "https://gutendex.com/books";

    private static final int TARGET_BOOKS_COUNT = 1000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private static final Path DEFAULT_OUTPUT_FILE =  Path.of(
            "elastic", "elastic-testing",
            "src", "test", "resources", "data", "json", "books.json"
    );

    private BooksDownloader() {
    }

    public static void main(String[] args) throws IOException {
        Path outputFile = resolveOutputFile(args);
        exportPopularBooks(TARGET_BOOKS_COUNT, outputFile);
    }

    public static void exportPopularBooks(Path outputFile) throws IOException {
        exportPopularBooks(TARGET_BOOKS_COUNT, outputFile);
    }

    public static void exportPopularBooks(int targetBooksCount, Path outputFile) throws IOException {
        if (targetBooksCount <= 0) {
            throw new IllegalArgumentException("targetBooksCount must be positive");
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("outputFile must not be null");
        }

        List<BookRecord> records = downloadPopularBooks(targetBooksCount);
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(records);
        Files.writeString(outputFile, json, StandardCharsets.UTF_8);
        log.info("Exported {} books to '{}'", records.size(), outputFile.toAbsolutePath());
    }

    private static List<BookRecord> downloadPopularBooks(int targetBooksCount) throws IOException {
        List<BookRecord> records = new ArrayList<>(targetBooksCount);
        Set<String> deduplicationKeys = new LinkedHashSet<>();
        String nextPageUrl = POPULAR_BOOKS_URL;

        while (nextPageUrl != null && records.size() < targetBooksCount) {
            GutendexResponse page = fetchPage(nextPageUrl);
            List<GutendexBook> books = safeList(page.getResults()).stream()
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
                        record.getRanking(),
                        targetBooksCount,
                        record.getName(),
                        record.getAuthor()
                );
            }

            nextPageUrl = page.getNext();
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

        return OBJECT_MAPPER.readValue(body, GutendexResponse.class);
    }

    private static Optional<BookRecord> toBookRecord(GutendexBook book, int ranking) {
        String title = sanitize(book.getTitle());
        if (title.isEmpty()) {
            return Optional.empty();
        }

        String author = firstAuthorName(book);
        if (author.isEmpty()) {
            author = "Unknown";
        }

        BookRecord record = new BookRecord();
        record.setName(title);
        record.setAuthor(author);
        record.setRanking(ranking);
        record.setReleaseYear(extractReleaseYear(book));
        record.setGenres(extractGenres(book));
        record.setSynopsis(extractSynopsis(book));
        return Optional.of(record);
    }

    private static String firstAuthorName(GutendexBook book) {
        return safeList(book.getAuthors()).stream()
                .map(GutendexAuthor::getName)
                .map(BooksDownloader::sanitize)
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElse("");
    }

    private static int extractReleaseYear(GutendexBook book) {
        return findYearInText(book.getTitle())
                .or(() -> safeList(book.getSubjects()).stream()
                        .map(BooksDownloader::findYearInText)
                        .flatMap(Optional::stream)
                        .findFirst())
                .orElse(0);
    }

    private static List<String> extractGenres(GutendexBook book) {
        List<String> bookshelves = safeList(book.getBookshelves()).stream()
                .map(BooksDownloader::sanitize)
                .filter(value -> !value.isEmpty())
                .limit(5)
                .toList();
        if (!bookshelves.isEmpty()) {
            return bookshelves;
        }

        List<String> subjects = safeList(book.getSubjects()).stream()
                .map(BooksDownloader::sanitize)
                .filter(value -> !value.isEmpty())
                .limit(5)
                .toList();
        if (!subjects.isEmpty()) {
            return subjects;
        }

        return List.of("Unknown");
    }

    private static String extractSynopsis(GutendexBook book) {
        return safeList(book.getSummaries()).stream()
                .map(BooksDownloader::sanitize)
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElseGet(() -> safeList(book.getSubjects()).stream()
                        .map(BooksDownloader::sanitize)
                        .filter(value -> !value.isEmpty())
                        .findFirst()
                        .orElse("Synopsis not available."));
    }

    private static String deduplicationKey(BookRecord record) {
        return "%s|%s".formatted(
                sanitize(record.getName()).toLowerCase(Locale.ROOT),
                sanitize(record.getAuthor()).toLowerCase(Locale.ROOT)
        );
    }

    private static long safeDownloadCount(GutendexBook book) {
        if (book.getDownloadCount() == null || book.getDownloadCount() < 0) {
            return 0L;
        }
        return book.getDownloadCount();
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

    private static Path resolveOutputFile(String[] args) {
        return Optional.ofNullable(args)
                .filter(value -> value.length > 0)
                .filter(value -> value[0] != null)
                .filter(value -> !value[0].isBlank())
                .map(value -> Path.of(value[0].trim()))
                .orElse(DEFAULT_OUTPUT_FILE);
    }

    private static MultiValueMap<String, String> defaultHeaders() {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.ACCEPT, "application/json");
        return headers;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
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

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GutendexResponse {
        private List<GutendexBook> results = List.of();
        private String next;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GutendexBook {
        private String title;
        private List<GutendexAuthor> authors = List.of();
        private List<String> subjects = List.of();
        private List<String> bookshelves = List.of();
        private List<String> summaries = List.of();
        @JsonProperty("download_count")
        private Long downloadCount;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GutendexAuthor {
        private String name;
    }

    @Data
    public static class BookRecord {
        private String name;
        private String author;
        private Integer ranking;
        @JsonProperty("release_year")
        private Integer releaseYear;
        private List<String> genres;
        private String synopsis;
    }
}
