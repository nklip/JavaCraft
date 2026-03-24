package my.javacraft.elastic.data.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@Slf4j
public final class MoviesDownloader implements JsonDownloader<MoviesDownloader.Movie> {

    private static final String IMDB_EXPORT_URL = "https://www.imdb.com/list/ls068082370/export?ref_=ttls_otexp";
    private static final String OMDB_URL_TEMPLATE = "http://www.omdbapi.com/?i=%s&apikey=1e60fc51";
    private static final int OMDB_REQUEST_PAUSE_MILLIS = 500;
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};
    private static final RestTemplate REST_TEMPLATE = new RestTemplate();
    private static final Path DEFAULT_OUTPUT_FILE = Path.of(
            "elastic", "elastic-testing",
            "src", "test", "resources", "data", "json", "movies.json"
    );

    private MoviesDownloader() {
    }

    public static void main(String[] args) throws IOException {
        log.info("Starting MoviesDownloader...");
        new MoviesDownloader().run(args);
    }

    @Override
    public String datasetName() {
        return "movies";
    }

    @Override
    public Path defaultOutputFile() {
        return DEFAULT_OUTPUT_FILE;
    }

    @Override
    public List<Movie> downloadRecords() throws IOException {
        log.info("Fetching movies...");
        return downloadTopMovies();
    }

    private static List<Movie> downloadTopMovies() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, br, zstd");
        headers.set(HttpHeaders.ACCEPT,
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,"
                        + "application/signed-exchange;v=b3;q=0.7");

        ResponseEntity<String> response = REST_TEMPLATE.exchange(
                IMDB_EXPORT_URL,
                HttpMethod.GET,
                new HttpEntity<>(null, headers),
                String.class
        );

        String csvBody = response.getBody();
        if (csvBody == null || csvBody.isBlank()) {
            throw new IOException("IMDb export returned an empty CSV response");
        }
        return transformCsvIntoMovies(csvBody);
    }

    static List<Movie> transformCsvIntoMovies(String csvBody) throws IOException {
        // CSV columns: Position,Const,Created,Modified,Description,Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors
        List<String> csvLines = csvBody.lines().toList();
        CSVParser parser = new CSVParserBuilder()
                .withSeparator(',')
                .withIgnoreQuotations(false)
                .build();

        List<Movie> movies = new ArrayList<>();
        for (int i = 1; i < csvLines.size(); i++) {
            String[] columns = parser.parseLine(csvLines.get(i));
            String imdbId   = columns[1];
            String title    = columns[5];
            String genres   = columns[11];
            String director = columns[14];

            log.info("Processing {}...", title);
            sleepBeforeOmdbRequest();

            String synopsis = fetchPlot(imdbId).orElse(columns[4]);
            List<String> genreList = Arrays.stream(genres.replace(" ", "").split(",")).toList();

            movies.add(new Movie(
                    director,
                    genreList,
                    title,
                    Integer.parseInt(columns[0]),
                    Integer.parseInt(columns[10]),
                    synopsis
            ));
        }
        return movies;
    }

    private static Optional<String> fetchPlot(String imdbId) {
        try {
            ResponseEntity<String> response = REST_TEMPLATE.exchange(
                    OMDB_URL_TEMPLATE.formatted(imdbId),
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    String.class
            );
            Map<String, Object> movieMap = SHARED_OBJECT_MAPPER.readValue(response.getBody(), MAP_TYPE_REF);
            return Optional.ofNullable((String) movieMap.get("Plot"));
        } catch (Exception e) {
            log.debug(e.getMessage());
            return Optional.empty();
        }
    }

    private static void sleepBeforeOmdbRequest() throws IOException {
        try {
            Thread.sleep(OMDB_REQUEST_PAUSE_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting between OMDB requests", ex);
        }
    }

    public record Movie(
            String director,
            List<String> genres,
            String name,
            Integer ranking,
            Integer releaseYear,
            String synopsis
    ) {}
}
