package dev.nklip.javacraft.elastic.data.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@Slf4j
@SuppressWarnings("SameParameterValue")
public final class MusicDownloader implements JsonDownloader<MusicDownloader.MusicRecord> {

    private static final RestTemplate REST_TEMPLATE = new RestTemplate();
    private static final List<String> TARGET_BANDS = List.of("ABBA", "Powerwolf", "Sabaton");
    private static final int TRACKS_PER_BAND = 80;
    private static final String ITUNES_SEARCH_URL =
            "https://itunes.apple.com/search?term=%s&entity=song&limit=200";
    private static final String LYRICS_OVH_URL = "https://api.lyrics.ovh/v1/%s/%s";
    private static final Path DEFAULT_OUTPUT_FILE = Path.of(
            "elastic", "elastic-testing",
            "src", "test", "resources", "data", "json", "music.json"
    );

    private MusicDownloader() {
    }

    public static void main(String[] args) throws IOException {
        log.info("Starting MusicDownloader...");
        new MusicDownloader().run(args);
    }

    @Override
    public String datasetName() {
        return "music records";
    }

    @Override
    public Path defaultOutputFile() {
        return DEFAULT_OUTPUT_FILE;
    }

    @Override
    public List<MusicRecord> downloadRecords() throws IOException {
        log.info("Fetching music records...");
        return downloadMusicRecords(TARGET_BANDS, TRACKS_PER_BAND);
    }

    private static List<MusicRecord> downloadMusicRecords(List<String> bands, int tracksPerBand) throws IOException {
        List<MusicRecord> records = new ArrayList<>();
        for (int i = 0; i < bands.size(); i++) {
            String band = bands.get(i);
            int bandIndex = i + 1;
            records.addAll(fetchMusicForBand(band, bandIndex, bands.size(), tracksPerBand));
        }
        return records;
    }

    private static List<MusicRecord> fetchMusicForBand(
            String band, int bandIndex, int bandSize, int tracksPerBand) throws IOException {
        String normalizedBand = band == null ? "" : band.trim();
        if (normalizedBand.isEmpty()) {
            return List.of();
        }

        String url = ITUNES_SEARCH_URL.formatted(encode(normalizedBand));
        ResponseEntity<byte[]> response = REST_TEMPLATE.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(null, defaultHeaders()),
                byte[].class
        );

        String body = decodeResponseBody(response.getBody());
        if (body.isBlank()) {
            log.warn("No iTunes response body for '{}'", normalizedBand);
            return List.of();
        }

        ItunesSearchResponse searchResponse = SHARED_OBJECT_MAPPER.readValue(body, ItunesSearchResponse.class);
        Map<String, ItunesSong> uniqueSongs = new LinkedHashMap<>();
        safeList(searchResponse.results()).stream()
                .filter(song -> song.artistName() != null)
                .filter(song -> normalizedBand.equalsIgnoreCase(song.artistName()))
                .filter(song -> song.collectionName() != null && !song.collectionName().isBlank())
                .filter(song -> song.trackName() != null && !song.trackName().isBlank())
                .sorted(Comparator
                        .comparing(ItunesSong::collectionName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(song -> safeTrackNumber(song.trackNumber()))
                        .thenComparing(ItunesSong::trackName, String.CASE_INSENSITIVE_ORDER))
                .forEach(song -> uniqueSongs.putIfAbsent(deduplicationKey(song), song));

        List<MusicRecord> records = new ArrayList<>();
        int processedSongs = 0;
        for (ItunesSong song : uniqueSongs.values()) {
            if (records.size() >= tracksPerBand) {
                break;
            }
            processedSongs++;
            MusicRecord record = new MusicRecord(
                    song.collectionName(),
                    normalizedBand,
                    fetchLyrics(song.artistName(), song.trackName()),
                    song.trackName(),
                    extractReleaseYear(song.releaseDate()),
                    safeTrackNumber(song.trackNumber())
            );
            records.add(record);

            // for example, bandIndex - 1, bandSize - 3, tracksPerBand - 80
            // processedTracks = 0..80; totalProcessedTracks = 240
            int processedTracks = ((bandIndex - 1) * tracksPerBand) + processedSongs;
            int totalProcessedTracks = bandSize * tracksPerBand;
            log.info(
                    "Downloaded track {}/{}: band='{}', album='{}', track_number={}, name='{}', release_year={}",
                    processedTracks,
                    totalProcessedTracks,
                    record.band(),
                    record.album(),
                    record.trackNumber(),
                    record.name(),
                    record.releaseYear()
            );
        }

        log.info("Collected {} tracks for '{}'", records.size(), normalizedBand);
        return records;
    }

    private static String fetchLyrics(String artist, String trackName) {
        try {
            String url = LYRICS_OVH_URL.formatted(encode(artist), encode(trackName));
            ResponseEntity<byte[]> response = REST_TEMPLATE.getForEntity(url, byte[].class);
            String body = decodeResponseBody(response.getBody());
            if (!response.getStatusCode().is2xxSuccessful() || body.isBlank()) {
                return "";
            }
            LyricsResponse lyricsResponse = SHARED_OBJECT_MAPPER.readValue(body, LyricsResponse.class);
            return Objects.requireNonNullElse(lyricsResponse.lyrics(), "");
        } catch (Exception ex) {
            log.debug("No lyrics for '{} - {}': {}", artist, trackName, ex.getMessage());
            return "";
        }
    }

    private static HttpHeaders defaultHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");
        return headers;
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Integer extractReleaseYear(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(releaseDate.substring(0, 4));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int safeTrackNumber(Integer trackNumber) {
        return trackNumber == null ? 0 : Math.max(trackNumber, 0);
    }

    private static String deduplicationKey(ItunesSong song) {
        return "%s|%d|%s".formatted(
                song.collectionName().toLowerCase(Locale.ROOT),
                safeTrackNumber(song.trackNumber()),
                song.trackName().toLowerCase(Locale.ROOT)
        );
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItunesSearchResponse(List<ItunesSong> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItunesSong(
            String artistName,
            String collectionName,
            Integer trackNumber,
            String trackName,
            String releaseDate
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LyricsResponse(String lyrics) {}

    public record MusicRecord(
            String album,
            String band,
            String lyrics,
            String name,
            @JsonProperty("release_year")
            Integer releaseYear,
            @JsonProperty("track_number")
            Integer trackNumber
    ) {}
}
