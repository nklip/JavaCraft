package dev.nklip.javacraft.shardshop.workload.dataset;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline development tool. Kept in test sources so services never package the generator. */
public final class BuyerDatasetGenerator {
    private static final String VERSION = "buyers-v1";
    private static final List<String> REGIONS = List.of("US", "EU", "ASIA");
    private static final List<String> DOMAINS = List.of("example.com", "example.net", "example.org");
    private static final List<String> EU_COUNTRIES = List.of("AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE",
            "ES", "FI", "FR", "GR", "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT",
            "RO", "SE", "SI", "SK");
    private static final List<String> ASIA_COUNTRIES = List.of("CN", "VN", "KR", "JP");
    private static final String NAME_HEADER = "name\temailPart\tcountryCode\tsourceRank\tsourceCount\tsource";
    private static final String PLACE_HEADER = "countryCode\tstreet\tcity\tpostalCode";
    private static final String BUYER_HEADER = "firstName\tsurname\temail\tphone\tline1\tcity\tpostalCode\tcountryCode\n";

    private BuyerDatasetGenerator() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected the dataset module directory; output is fixed under its target directory");
        }
        Path output = generate(Path.of(arguments[0]));
        System.out.println("Generated 1,000 buyers per region (3,000 total) in " + output);
    }

    /** Reads only retained pools; validates every region before writing any output. */
    static Path generate(Path module) throws IOException {
        Path directory = module.toAbsolutePath().normalize();
        Path output = directory.resolve("target/generated-datasets/" + VERSION);
        rejectOutputSymlinks(directory, output);
        Map<String, Map<String, String>> places = readPlaces(directory.resolve("sources/address-patterns.tsv"));
        Set<String> emails = new HashSet<>();
        Map<String, String> generated = new LinkedHashMap<>();
        for (String region : REGIONS) {
            List<Map<String, String>> given = readNames(directory, region, "given");
            List<Map<String, String>> surnames = readNames(directory, region, "surname");
            generated.put(region, generateRegion(region, given, surnames, places, emails));
        }
        Files.createDirectories(output);
        for (var region : generated.entrySet()) {
            Files.writeString(output.resolve(region.getKey() + ".tsv"), region.getValue(), StandardCharsets.UTF_8);
        }
        return output;
    }

    private static void rejectOutputSymlinks(Path module, Path output) throws IOException {
        Path current = module;
        for (Path component : module.relativize(output)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Generator output must stay under the module target directory: " + current);
            }
        }
        for (String region : REGIONS) {
            if (Files.isSymbolicLink(output.resolve(region + ".tsv"))) {
                throw new IOException("Generator output must not overwrite a symbolic link: " + region);
            }
        }
    }

    private static List<String> countries(String region) {
        return switch (region) {
            case "US" -> List.of("US");
            case "EU" -> EU_COUNTRIES;
            case "ASIA" -> ASIA_COUNTRIES;
            default -> throw new IllegalArgumentException("Unsupported region: " + region);
        };
    }

    private static Map<String, Map<String, String>> readPlaces(Path file) throws IOException {
        Map<String, Map<String, String>> places = new LinkedHashMap<>();
        for (Map<String, String> row : readRows(file, PLACE_HEADER)) {
            for (String column : PLACE_HEADER.split("\t")) {
                requireValue(file, row, column);
            }
            if (places.putIfAbsent(row.get("countryCode"), row) != null) {
                throw new IllegalArgumentException(file + ": duplicate country " + row.get("countryCode"));
            }
        }
        Set<String> expected = new HashSet<>();
        REGIONS.forEach(region -> expected.addAll(countries(region)));
        if (!places.keySet().equals(expected)) {
            throw new IllegalArgumentException(file + ": address countries must cover US, EU27 and CN/VN/KR/JP exactly");
        }
        return places;
    }

    private static List<Map<String, String>> readNames(Path module, String region, String kind) throws IOException {
        Path file = module.resolve("sources/names/" + region + "-" + kind + ".tsv");
        List<Map<String, String>> rows = readRows(file, NAME_HEADER);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(file + ": regional name pools must not be empty");
        }
        for (Map<String, String> row : rows) {
            requireValue(file, row, "name");
            if (!row.get("emailPart").matches("[a-z0-9]+")) {
                throw new IllegalArgumentException(file + ": emailPart must contain lowercase ASCII letters or digits");
            }
            if (!countries(region).contains(row.get("countryCode"))) {
                throw new IllegalArgumentException(file + ": country outside " + region);
            }
        }
        return rows;
    }

    private static String generateRegion(String region, List<Map<String, String>> firstNames,
                                         List<Map<String, String>> surnames, Map<String, Map<String, String>> places,
                                         Set<String> emails) {
        StringBuilder output = new StringBuilder(BUYER_HEADER);
        List<String> countries = countries(region);
        for (int index = 0; index < 1000; index++) {
            String country = countries.get(index % countries.size());
            // EU is a single market; only ASIA pairs names with their source country.
            List<Map<String, String>> givenPool = regionalPool(region, country, firstNames);
            List<Map<String, String>> surnamePool = regionalPool(region, country, surnames);
            if (givenPool.isEmpty() || surnamePool.isEmpty()) {
                throw new IllegalArgumentException(region + ": every selected country needs given names and surnames: " + country);
            }
            int ordinal = region.equals("ASIA") ? index / countries.size() : index;
            Map<String, String> given = givenPool.get(ordinal % givenPool.size());
            int start = number(region + ":" + index + ":surname", surnamePool.size());
            Map<String, String> surname = null;
            String email = null;
            for (int offset = 0; offset < DOMAINS.size() * surnamePool.size(); offset++) {
                Map<String, String> candidate = surnamePool.get((start + offset) % surnamePool.size());
                String domain = DOMAINS.get((ordinal + offset / surnamePool.size()) % DOMAINS.size());
                String candidateEmail = given.get("emailPart") + "." + candidate.get("emailPart") + "@" + domain;
                if (emails.add(candidateEmail)) {
                    surname = candidate;
                    email = candidateEmail;
                    break;
                }
            }
            if (surname == null) {
                throw new IllegalArgumentException(region + ": name pool cannot provide another unique email at row " + (index + 1));
            }
            int phoneSuffix = number(region + ":" + index + ":phone", 100);
            String phone = "+120255501" + (phoneSuffix < 10 ? "0" : "") + phoneSuffix;
            Map<String, String> place = places.get(country);
            output.append(String.join("\t", given.get("name"), surname.get("name"), email, phone,
                    (index + 1) + " " + place.get("street"), place.get("city"), place.get("postalCode"), country));
            output.append('\n');
        }
        return output.toString();
    }

    private static List<Map<String, String>> regionalPool(String region, String country, List<Map<String, String>> names) {
        return region.equals("ASIA") ? names.stream().filter(row -> row.get("countryCode").equals(country)).toList() : names;
    }

    /** Unsigned SHA-256 modulo, matching the original buyers-v1 coordinates without a PRNG. */
    private static int number(String label, int modulus) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((VERSION + ":" + label).getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest).mod(BigInteger.valueOf(modulus)).intValueExact();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK must provide SHA-256", impossible);
        }
    }

    /** Frozen inputs use unquoted TSV cells; reject malformed input instead of silently changing fixtures. */
    private static List<Map<String, String>> readRows(Path file, String expectedHeader) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals(expectedHeader)) {
            throw new IllegalArgumentException(file + ": unexpected TSV header");
        }
        String[] header = expectedHeader.split("\t");
        List<Map<String, String>> rows = new ArrayList<>();
        for (int line = 1; line < lines.size(); line++) {
            String[] cells = lines.get(line).split("\t", -1);
            if (cells.length != header.length || lines.get(line).contains("\"")) {
                throw new IllegalArgumentException(file + ": malformed unquoted TSV row " + (line + 1));
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.length; column++) {
                row.put(header[column], cells[column]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static void requireValue(Path file, Map<String, String> row, String column) {
        if (row.get(column).isBlank()) {
            throw new IllegalArgumentException(file + ": " + column + " must not be blank");
        }
    }
}
