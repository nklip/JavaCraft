package dev.nklip.javacraft.elastic.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@Slf4j
@SuppressWarnings("SameParameterValue")
public final class MoviesDownloader implements JsonDownloader<MoviesDownloader.Movie> {

    private static final String IMDB_RATINGS_URL      = "https://datasets.imdbws.com/title.ratings.tsv.gz";
    private static final String IMDB_BASICS_URL       = "https://datasets.imdbws.com/title.basics.tsv.gz";
    private static final String IMDB_CREW_URL         = "https://datasets.imdbws.com/title.crew.tsv.gz";
    private static final String IMDB_NAMES_URL        = "https://datasets.imdbws.com/name.basics.tsv.gz";
    private static final String OMDB_URL_TEMPLATE     = "http://www.omdbapi.com/?i=%s&apikey=1e60fc51";
    private static final String IMDB_NULL             = "\\N";
    private static final int    TOP_MOVIES_COUNT      = 250;
    private static final long   MIN_VOTES             = 50_000L;
    private static final int    OMDB_PAUSE_MILLIS     = 500;

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};
    private static final RestTemplate REST_TEMPLATE = new RestTemplate();
    private static final Path DEFAULT_OUTPUT_FILE = Path.of(
            "elastic", "elastic-testing",
            "src", "test", "resources", "data", "json", "movies.json"
    );

    private MoviesDownloader() {}

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
        log.info("Loading ratings...");
        Map<String, Long> ratingsByTconst;
        try (BufferedReader reader = openGzip(IMDB_RATINGS_URL)) {
            ratingsByTconst = parseRatings(reader, MIN_VOTES);
        }
        log.debug("Loaded {} titles with ≥{} votes", ratingsByTconst.size(), MIN_VOTES);

        log.info("Loading movie basics...");
        List<ImdbEntry> topMovies;
        try (BufferedReader reader = openGzip(IMDB_BASICS_URL)) {
            topMovies = parseTopMovies(reader, ratingsByTconst, TOP_MOVIES_COUNT);
        }

        log.info("Loading crew data...");
        Set<String> topTconsts = new HashSet<>();
        for (ImdbEntry entry : topMovies) {
            topTconsts.add(entry.tconst());
        }
        Map<String, String> directorNconstByTconst;
        try (BufferedReader reader = openGzip(IMDB_CREW_URL)) {
            directorNconstByTconst = parseDirectorNconsts(reader, topTconsts);
        }

        log.info("Loading director names...");
        Set<String> neededNconsts = new HashSet<>(directorNconstByTconst.values());
        Map<String, String> nameByNconst;
        try (BufferedReader reader = openGzip(IMDB_NAMES_URL)) {
            nameByNconst = parseDirectorNames(reader, neededNconsts);
        }

        log.info("Assembling {} movies with OMDB synopses...", topMovies.size());
        List<Movie> movies = new ArrayList<>(topMovies.size());
        for (int i = 0; i < topMovies.size(); i++) {
            ImdbEntry entry = topMovies.get(i);
            String directorNconst = directorNconstByTconst.getOrDefault(entry.tconst(), "");
            String director       = nameByNconst.getOrDefault(directorNconst, "");

            log.info("Processing ({}/{}): {} ...", i + 1, topMovies.size(), entry.title());
            sleepBeforeOmdbRequest();
            String synopsis = fetchPlot(entry.tconst()).orElse("");

            movies.add(new Movie(director, entry.genres(), entry.title(), i + 1, entry.year(), synopsis));
        }
        return movies;
    }

    // --- TSV parsers (package-private for testing) ---

    /**
     * Reads title.ratings.tsv and returns a map of tconst → numVotes,
     * keeping only titles that meet the minimum vote threshold.
     */
    static Map<String, Long> parseRatings(BufferedReader reader, long minVotes) throws IOException {
        // header: tconst  averageRating  numVotes
        Map<String, Long> result = new HashMap<>();
        reader.readLine();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] cols = line.split("\t", 3);
            if (cols.length < 3 || IMDB_NULL.equals(cols[2])) {
                continue;
            }
            long numVotes = Long.parseLong(cols[2]);
            if (numVotes >= minVotes) {
                result.put(cols[0], numVotes);
            }
        }
        return result;
    }

    /**
     * Reads title.basics.tsv, joins with the provided ratings map, and returns
     * the top {@code limit} movies sorted by vote count descending.
     */
    static List<ImdbEntry> parseTopMovies(
            BufferedReader reader, Map<String, Long> ratingsByTconst, int limit) throws IOException {
        // header: tconst  titleType  primaryTitle  originalTitle  isAdult  startYear  endYear  runtimeMinutes  genres
        List<ImdbEntry> candidates = new ArrayList<>();
        reader.readLine();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] cols = line.split("\t", 9);
            if (cols.length < 9 || !"movie".equals(cols[1])) {
                continue;
            }
            String tconst = cols[0];
            Long numVotes = ratingsByTconst.get(tconst);
            if (numVotes == null) {
                continue;
            }
            int year = IMDB_NULL.equals(cols[5]) ? 0 : Integer.parseInt(cols[5]);
            List<String> genres = IMDB_NULL.equals(cols[8])
                    ? List.of()
                    : List.of(cols[8].split(","));
            candidates.add(new ImdbEntry(tconst, cols[2], year, genres, numVotes));
        }
        return candidates.stream()
                .sorted(Comparator.comparingLong(ImdbEntry::numVotes).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Reads title.crew.tsv and returns a map of tconst → first director nconst
     * for the given set of tconsts.
     */
    static Map<String, String> parseDirectorNconsts(
            BufferedReader reader, Set<String> tconstSet) throws IOException {
        // header: tconst  directors  writers
        Map<String, String> result = new HashMap<>();
        reader.readLine();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] cols = line.split("\t", 3);
            if (cols.length < 2 || !tconstSet.contains(cols[0])) {
                continue;
            }
            String directors = cols[1];
            if (!IMDB_NULL.equals(directors) && !directors.isBlank()) {
                result.put(cols[0], directors.split(",")[0]);
            }
        }
        return result;
    }

    /**
     * Reads name.basics.tsv and returns a map of nconst → primaryName
     * for the given set of nconsts.
     */
    static Map<String, String> parseDirectorNames(
            BufferedReader reader, Set<String> nconstSet) throws IOException {
        // header: nconst  primaryName  birthYear  deathYear  primaryProfession  knownForTitles
        Map<String, String> result = new HashMap<>();
        reader.readLine();
        String line;
        while ((line = reader.readLine()) != null && result.size() < nconstSet.size()) {
            String[] cols = line.split("\t", 3);
            if (cols.length >= 2 && nconstSet.contains(cols[0])) {
                result.put(cols[0], cols[1]);
            }
        }
        return result;
    }

    // --- Helpers ---

    private static BufferedReader openGzip(String url) throws IOException {
        return new BufferedReader(
                new InputStreamReader(
                        new GZIPInputStream(URI.create(url).toURL().openStream()),
                        StandardCharsets.UTF_8
                )
        );
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
            Thread.sleep(OMDB_PAUSE_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting between OMDB requests", ex);
        }
    }

    record ImdbEntry(String tconst, String title, int year, List<String> genres, long numVotes) {}

    public record Movie(
            String director,
            List<String> genres,
            String name,
            Integer ranking,
            @JsonProperty("release_year")
            Integer releaseYear,
            String synopsis
    ) {}
}
