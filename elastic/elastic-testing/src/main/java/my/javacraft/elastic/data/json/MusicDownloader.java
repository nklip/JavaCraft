package my.javacraft.elastic.data.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Slf4j
public final class MusicDownloader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RestTemplate REST_TEMPLATE = new RestTemplate();
    private static final List<String> TARGET_BANDS = List.of("ABBA", "Powerwolf", "Sabaton");
    private static final int TRACKS_PER_BAND = 80;
    private static final String ITUNES_SEARCH_URL =
            "https://itunes.apple.com/search?term=%s&entity=song&limit=200";
    private static final String LYRICS_OVH_URL = "https://api.lyrics.ovh/v1/%s/%s";
    private static final Path DEFAULT_OUTPUT_FILE =  Path.of(
            "elastic", "elastic-testing",
                    "src", "test", "resources", "data", "json", "music.json"
    );

    private MusicDownloader() {
    }

    public static void main(String[] args) throws IOException {
        Path outputFile = resolveOutputFile(args);
        exportMusic(outputFile);
    }

    private static Path resolveOutputFile(String[] args) {
        return Optional.ofNullable(args)
                .filter(a -> a.length > 0)
                .filter(a -> a[0] != null)
                .filter(a -> !a[0].isBlank())
                .map(a -> Path.of(a[0].trim()))
                .orElse(DEFAULT_OUTPUT_FILE);
    }

    public static void exportMusic(Path outputFile) throws IOException {
        exportMusic(TARGET_BANDS, TRACKS_PER_BAND, outputFile);
    }

    public static void exportMusic(List<String> bands, int tracksPerBand, Path outputFile) throws IOException {
        if (bands == null || bands.isEmpty()) {
            throw new IllegalArgumentException("Bands list must not be empty");
        }
        if (tracksPerBand <= 0) {
            throw new IllegalArgumentException("tracksPerBand must be positive");
        }

        List<MusicRecord> records = new ArrayList<>();
        for (String band : bands) {
            records.addAll(fetchMusicForBand(band, tracksPerBand));
        }

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(records);
        Files.writeString(outputFile, json, StandardCharsets.UTF_8);
        log.info("Exported {} records to '{}'", records.size(), outputFile.toAbsolutePath());
    }

    private static List<MusicRecord> fetchMusicForBand(String band, int tracksPerBand) throws IOException {
        String normalizedBand = band == null ? "" : band.trim();
        if (normalizedBand.isEmpty()) {
            return List.of();
        }

        String url = ITUNES_SEARCH_URL.formatted(encodeQuery(normalizedBand));
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

        ItunesSearchResponse searchResponse = OBJECT_MAPPER.readValue(body, ItunesSearchResponse.class);
        Map<String, ItunesSong> uniqueSongs = new LinkedHashMap<>();
        searchResponse.getResults().stream()
                .filter(song -> song.getArtistName() != null)
                .filter(song -> normalizedBand.equalsIgnoreCase(song.getArtistName()))
                .filter(song -> song.getCollectionName() != null && !song.getCollectionName().isBlank())
                .filter(song -> song.getTrackName() != null && !song.getTrackName().isBlank())
                .sorted(Comparator
                        .comparing(ItunesSong::getCollectionName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(song -> safeTrackNumber(song.getTrackNumber()))
                        .thenComparing(ItunesSong::getTrackName, String.CASE_INSENSITIVE_ORDER))
                .forEach(song -> uniqueSongs.putIfAbsent(
                        deduplicationKey(song),
                        song
                ));

        List<MusicRecord> records = new ArrayList<>();
        int count = 0;
        for (ItunesSong song : uniqueSongs.values()) {
            if (count >= tracksPerBand) {
                break;
            }
            MusicRecord record = new MusicRecord();
            record.setBand(normalizedBand);
            record.setAlbum(song.getCollectionName());
            record.setTrackNumber(safeTrackNumber(song.getTrackNumber()));
            record.setName(song.getTrackName());
            record.setReleaseYear(extractReleaseYear(song.getReleaseDate()));
            record.setLyrics(fetchLyrics(song.getArtistName(), song.getTrackName()));
            records.add(record);
            log.info(
                    "Downloaded track: band='{}', album='{}', track_number={}, name='{}', release_year={}",
                    record.getBand(),
                    record.getAlbum(),
                    record.getTrackNumber(),
                    record.getName(),
                    record.getReleaseYear()
            );
            count++;
        }

        log.info("Collected {} tracks for '{}'", records.size(), normalizedBand);
        return records;
    }

    private static String fetchLyrics(String artist, String trackName) {
        try {
            String url = LYRICS_OVH_URL.formatted(encodePath(artist), encodePath(trackName));
            ResponseEntity<byte[]> response = REST_TEMPLATE.getForEntity(url, byte[].class);
            String body = decodeResponseBody(response.getBody());
            if (!response.getStatusCode().is2xxSuccessful() || body.isBlank()) {
                return "";
            }
            LyricsResponse lyricsResponse = OBJECT_MAPPER.readValue(body, LyricsResponse.class);
            return lyricsResponse.getLyrics() == null ? "" : lyricsResponse.getLyrics();
        } catch (Exception ex) {
            log.debug("No lyrics for '{} - {}': {}", artist, trackName, ex.getMessage());
            return "";
        }
    }

    private static MultiValueMap<String, String> defaultHeaders() {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
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

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Integer extractReleaseYear(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return 0;
        }
        String firstFourChars = releaseDate.substring(0, 4);
        try {
            return Integer.parseInt(firstFourChars);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int safeTrackNumber(Integer trackNumber) {
        return trackNumber == null ? 0 : Math.max(trackNumber, 0);
    }

    private static String deduplicationKey(ItunesSong song) {
        return "%s|%d|%s".formatted(
                song.getCollectionName().toLowerCase(Locale.ROOT),
                safeTrackNumber(song.getTrackNumber()),
                song.getTrackName().toLowerCase(Locale.ROOT)
        );
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ItunesSearchResponse {
        private List<ItunesSong> results = List.of();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ItunesSong {
        private String artistName;
        private String collectionName;
        private Integer trackNumber;
        private String trackName;
        private String releaseDate;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LyricsResponse {
        private String lyrics;
    }

    @Data
    public static class MusicRecord {
        private String band;
        private String album;
        @JsonProperty("track_number")
        private Integer trackNumber;
        private String name;
        @JsonProperty("release_year")
        private Integer releaseYear;
        private String lyrics;
    }
}
