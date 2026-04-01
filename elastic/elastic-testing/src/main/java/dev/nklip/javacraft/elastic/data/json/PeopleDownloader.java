package dev.nklip.javacraft.elastic.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.opencsv.CSVReaderHeaderAware;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

@Slf4j
@SuppressWarnings("SameParameterValue")
public final class PeopleDownloader implements JsonDownloader<PeopleDownloader.PersonRecord> {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String USER_AGENT =
            "JavaCraftPeopleDownloader/1.0 (https://github.com/NikitaLipatov/JavaCraft)";
    private static final String PANTHEON_DATASET_URL =
            "https://storage.googleapis.com/pantheon-public-data/person_2025_update.csv.bz2";
    private static final int TARGET_PEOPLE_COUNT = 100;
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("([+-]?\\d{4,})-(\\d{2})-(\\d{2})");
    private static final Pattern YEAR_PATTERN = Pattern.compile("([+-]?\\d{1,6})");

    private static final Path DEFAULT_OUTPUT_FILE = Path.of(
            "elastic", "elastic-testing",
            "src", "test", "resources", "data", "json", "people.json"
    );

    private PeopleDownloader() {
    }

    public static void main(String[] args) throws IOException {
        log.info("Starting PeopleDownloader...");
        new PeopleDownloader().run(args);
    }

    @Override
    public String datasetName() {
        return "people";
    }

    @Override
    public Path defaultOutputFile() {
        return DEFAULT_OUTPUT_FILE;
    }

    @Override
    public List<PersonRecord> downloadRecords() throws IOException {
        log.info("Fetching people...");
        return downloadMostFamousPeople(TARGET_PEOPLE_COUNT);
    }

    private static List<PersonRecord> downloadMostFamousPeople(int targetPeopleCount) throws IOException {
        List<PantheonPerson> topPeople = readTopPantheonPeople(targetPeopleCount);
        return toPersonRecords(topPeople, Clock.systemUTC(), person -> "");
    }

    private static List<PantheonPerson> readTopPantheonPeople(int targetPeopleCount) throws IOException {
        PriorityQueue<PantheonPerson> topPeople = new PriorityQueue<>(Comparator.comparingDouble(PantheonPerson::hpi));

        try (InputStream datasetStream = openPantheonDatasetStream();
             BZip2CompressorInputStream bzip2Stream = new BZip2CompressorInputStream(datasetStream);
             BufferedReader reader = new BufferedReader(new InputStreamReader(bzip2Stream, StandardCharsets.UTF_8));
             CSVReaderHeaderAware csvReader = new CSVReaderHeaderAware(reader)) {

            Map<String, String> row;
            while ((row = csvReader.readMap()) != null) {
                Optional<PantheonPerson> maybePerson = toPantheonPerson(row);
                if (maybePerson.isEmpty()) {
                    continue;
                }

                PantheonPerson person = maybePerson.get();
                if (topPeople.size() < targetPeopleCount) {
                    topPeople.offer(person);
                    continue;
                }

                PantheonPerson currentLowest = topPeople.peek();
                if (currentLowest != null && person.hpi() > currentLowest.hpi()) {
                    topPeople.poll();
                    topPeople.offer(person);
                }
            }
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        } catch (Exception ex) {
            throw new IOException("Unable to parse Pantheon people dataset", ex);
        }

        List<PantheonPerson> sortedPeople = new ArrayList<>(topPeople);
        sortedPeople.sort(Comparator.comparingDouble(PantheonPerson::hpi).reversed());
        return sortedPeople;
    }

    static List<PersonRecord> toPersonRecords(
            List<PantheonPerson> people,
            Clock clock,
            BiographyProvider biographyProvider) {
        List<PersonRecord> records = new ArrayList<>();

        for (PantheonPerson person : people) {
            PersonRecord record = toPersonRecord(records.size() + 1, person, clock, biographyProvider);
            records.add(record);
            log.info(
                    "Downloaded person {}/{}: '{} {}'",
                    record.ranking(),
                    people.size(),
                    record.name(),
                    record.surname()
            );
        }

        return records;
    }

    private static PersonRecord toPersonRecord(
            int ranking,
            PantheonPerson person,
            Clock clock,
            BiographyProvider biographyProvider) {
        NameParts nameParts = resolveNameParts(person.fullName());
        String biography = sanitize(biographyProvider.getBiography(person));
        String dateOfBirth = normalizeDate(person.birthDate(), person.birthYear());
        String dateOfDeath = normalizeDate(person.deathDate(), person.deathYear());
        return new PersonRecord(
                calculateAge(dateOfBirth, dateOfDeath, clock),
                dateOfBirth,
                dateOfDeath,
                nameParts.name(),
                ranking,
                resolveBiography(biography, person),
                nameParts.surname()
        );
    }

    static NameParts resolveNameParts(String fullName) {
        String normalizedFullName = sanitize(fullName);
        List<String> parts = List.of(normalizedFullName.split(" "));
        if (parts.size() == 1) {
            return new NameParts(normalizedFullName, "");
        }
        String surname = parts.getLast();
        String name = sanitize(normalizedFullName.substring(0, normalizedFullName.length() - surname.length()));
        return new NameParts(name, surname);
    }

    static String normalizeDate(String rawDate, String rawYear) {
        String normalizedDate = sanitize(rawDate);
        if (!normalizedDate.isEmpty()) {
            boolean beforeCommonEra = normalizedDate.toUpperCase(Locale.ROOT).endsWith(" BC");
            String withoutEra = sanitize(normalizedDate.replace("BC", ""));
            Matcher matcher = ISO_DATE_PATTERN.matcher(withoutEra);
            if (matcher.find()) {
                String year = normalizeYearSign(matcher.group(1));
                if (beforeCommonEra && !year.startsWith("-")) {
                    year = "-" + year;
                }
                return "%s-%s-%s".formatted(year, matcher.group(2), matcher.group(3));
            }
            return withoutEra;
        }

        String normalizedYear = sanitize(rawYear);
        if (normalizedYear.isEmpty()) {
            return "";
        }
        return normalizeYearSign(normalizedYear);
    }

    static Integer calculateAge(String dateOfBirth, String dateOfDeath, Clock clock) {
        String normalizedBirth = sanitize(dateOfBirth);
        if (normalizedBirth.isEmpty()) {
            return null;
        }

        Optional<LocalDate> birthDate = parseExactDate(normalizedBirth);
        String normalizedDeath = sanitize(dateOfDeath);
        Optional<LocalDate> deathDate = parseExactDate(normalizedDeath);
        Integer deathYear = parseYear(normalizedDeath);
        if (birthDate.isPresent()) {
            if (deathDate.isEmpty() && !normalizedDeath.isEmpty() && deathYear != null) {
                int ageByYears = deathYear - birthDate.get().getYear();
                return ageByYears >= 0 ? ageByYears : null;
            }

            LocalDate endDate = deathDate.orElse(LocalDate.now(clock));
            if (endDate.isBefore(birthDate.get())) {
                return null;
            }
            return Math.toIntExact(ChronoUnit.YEARS.between(birthDate.get(), endDate));
        }

        Integer birthYear = parseYear(normalizedBirth);
        if (birthYear == null) {
            return null;
        }

        Integer endYear = Optional.ofNullable(parseYear(sanitize(dateOfDeath)))
                .orElse(LocalDate.now(clock).getYear());
        if (endYear < birthYear) {
            return null;
        }
        return endYear - birthYear;
    }

    static String resolveBiography(String biography, PantheonPerson person) {
        String normalizedBiography = sanitize(biography);
        if (!normalizedBiography.isEmpty()) {
            return firstSentence(normalizedBiography);
        }

        String occupation = formatOccupation(person.occupation());
        String birthPlaceName = sanitize(person.birthPlaceName());
        String birthPlaceCountry = sanitize(person.birthPlaceCountry());

        if (!occupation.isEmpty() && !birthPlaceName.isEmpty() && !birthPlaceCountry.isEmpty()) {
            return "%s born in %s, %s.".formatted(occupation, birthPlaceName, birthPlaceCountry);
        }
        if (!occupation.isEmpty() && !birthPlaceCountry.isEmpty()) {
            return "%s from %s.".formatted(occupation, birthPlaceCountry);
        }
        if (!occupation.isEmpty()) {
            return occupation + ".";
        }
        return "Biography not available.";
    }

    static Optional<PantheonPerson> toPantheonPerson(Map<String, String> row) {
        if (row == null || Boolean.parseBoolean(sanitize(row.get("is_group")))) {
            return Optional.empty();
        }

        String fullName = sanitize(row.get("name"));
        String slug = sanitize(row.get("slug"));
        if (fullName.isEmpty() || slug.isEmpty()) {
            return Optional.empty();
        }

        Double hpi = parseDouble(row.get("hpi"));
        if (hpi == null) {
            return Optional.empty();
        }

        return Optional.of(new PantheonPerson(
                fullName,
                slug,
                sanitize(row.get("occupation")),
                sanitize(row.get("bplace_name")),
                sanitize(row.get("bplace_country")),
                sanitize(row.get("birthdate")),
                sanitize(row.get("birthyear")),
                sanitize(row.get("deathdate")),
                sanitize(row.get("deathyear")),
                hpi
        ));
    }

    private static InputStream openPantheonDatasetStream() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(PANTHEON_DATASET_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "Unable to fetch Pantheon dataset: HTTP %d".formatted(response.statusCode())
            );
        }
        return response.body();
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException {
        try {
            return HTTP_CLIENT.send(request, bodyHandler);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", ex);
        }
    }

    private static Optional<LocalDate> parseExactDate(String normalizedDate) {
        Matcher matcher = ISO_DATE_PATTERN.matcher(normalizedDate);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(
                    Integer.parseInt(normalizeYearSign(matcher.group(1))),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            ));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static Integer parseYear(String normalizedDate) {
        Matcher matcher = YEAR_PATTERN.matcher(sanitize(normalizedDate));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(normalizeYearSign(matcher.group(1)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        String normalized = sanitize(value);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstSentence(String value) {
        String normalized = sanitize(value);
        int sentenceEnd = normalized.indexOf(". ");
        if (sentenceEnd >= 0) {
            return normalized.substring(0, sentenceEnd + 1);
        }
        return normalized;
    }

    private static String formatOccupation(String occupation) {
        String normalized = sanitize(occupation).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String normalizeYearSign(String year) {
        return year.startsWith("+") ? year.substring(1) : year;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    @FunctionalInterface
    interface BiographyProvider {
        String getBiography(PantheonPerson person);
    }

    record NameParts(String name, String surname) {
    }

    record PantheonPerson(
            String fullName,
            String slug,
            String occupation,
            String birthPlaceName,
            String birthPlaceCountry,
            String birthDate,
            String birthYear,
            String deathDate,
            String deathYear,
            double hpi
    ) {
    }

    public record PersonRecord(
            Integer age,
            @JsonProperty("date_of_birth")
            String dateOfBirth,
            @JsonProperty("date_of_death")
            String dateOfDeath,
            String name,
            Integer ranking,
            @JsonProperty("reasons_for_being_famous")
            String reasonsForBeingFamous,
            String surname
    ) {}
}
