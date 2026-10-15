package dev.nklip.javacraft.shardshop.workload.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The retained name pools and source files, and the buyer and seller rows that SOURCES.md derives from them. */
class DatasetSourcesTest {
    private static final Map<String, Set<String>> COUNTRIES = Map.of(
            "US", Set.of("US"),
            "EU", Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
                    "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"),
            "ASIA", Set.of("CN", "VN", "KR", "JP"));

    @Test
    void namePoolsHaveUniqueNamesOfTheirRegionCountries() throws IOException {
        for (String region : COUNTRIES.keySet()) {
            for (String kind : List.of("given", "surname")) {
                String pool = region + "-" + kind;
                List<Map<String, String>> rows = rows(Path.of("sources/names/" + pool + ".tsv"));
                assertFalse(rows.isEmpty(), pool + " has names");
                if (!region.equals("ASIA")) {
                    assertEquals(1000, rows.size(), pool + " keeps the 1,000 most frequent names");
                }
                assertEquals(rows.size(), rows.stream().map(row -> row.get("countryCode") + "/" + row.get("name"))
                        .distinct().count(), pool + " has each name of a country once");
                for (Map<String, String> row : rows) {
                    assertTrue(COUNTRIES.get(region).contains(row.get("countryCode")), pool + " country " + row);
                    assertTrue(row.get("emailPart").matches("[a-z0-9]+"), pool + " email part " + row);
                    if (region.equals("ASIA")) {
                        assertTrue(row.get("source").startsWith("faker-") && row.get("sourceRank").isEmpty()
                                && row.get("sourceCount").isEmpty(), pool + " Faker names have no rank " + row);
                    }
                }
                if (region.equals("ASIA")) {
                    assertEquals(COUNTRIES.get("ASIA"), rows.stream().map(row -> row.get("countryCode"))
                            .collect(Collectors.toSet()), pool + " covers each Asian country");
                }
            }
        }
    }

    @Test
    void buyerEmailsPairTheNamesOfThePools() throws IOException {
        Map<String, List<Map<String, String>>> pools = new HashMap<>();
        for (String region : COUNTRIES.keySet()) {
            for (String kind : List.of("given", "surname")) {
                pools.put(region + "-" + kind, rows(Path.of("sources/names/" + region + "-" + kind + ".tsv")));
            }
        }
        for (var buyer : new BuyerDataset(BuyerDataset.VERSION).buyers()) {
            String[] parts = buyer.email().substring(0, buyer.email().indexOf('@')).split("\\.");
            assertEquals(2, parts.length, buyer.key() + " email has a given name and a surname");
            assertTrue(paired(pools.get(buyer.region() + "-given"), buyer.firstName(), parts[0], buyer),
                    buyer.key() + " first name and email");
            assertTrue(paired(pools.get(buyer.region() + "-surname"), buyer.surname(), parts[1], buyer),
                    buyer.key() + " surname and email");
        }
    }

    @Test
    void buyersLiveInEveryCountryOfTheirRegion() {
        Map<String, List<BuyerDataset.BuyerDefinition>> regions = new BuyerDataset(BuyerDataset.VERSION).buyersByRegion();
        for (String region : COUNTRIES.keySet()) {
            assertEquals(COUNTRIES.get(region), regions.get(region).stream()
                    .map(buyer -> buyer.address().countryCode()).collect(Collectors.toSet()),
                    region + " buyers live in each country of the region");
        }
        Map<String, Long> asia = regions.get("ASIA").stream()
                .collect(Collectors.groupingBy(buyer -> buyer.address().countryCode(), Collectors.counting()));
        assertEquals(Map.of("CN", 250L, "VN", 250L, "KR", 250L, "JP", 250L), asia, "ASIA buyers are spread evenly");
    }

    @Test
    void sellersAreCompaniesOfTheirRegionCountries() throws IOException {
        for (String region : COUNTRIES.keySet()) {
            for (Map<String, String> row : rows(Path.of("src/main/resources/datasets/catalog-v1/" + region + ".tsv"))) {
                assertTrue(COUNTRIES.get(region).contains(row.get("countryCode")), region + " seller " + row);
            }
        }
    }

    @Test
    void retainedFilesMatchTheirManifestHashes() throws IOException, NoSuchAlgorithmException {
        JsonNode retained = new ObjectMapper().readTree(Path.of("source-manifest.json").toFile()).get("retainedFiles");
        assertTrue(retained.size() > 0, "The manifest retains files");
        for (var file : retained.properties()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(file.getKey())));
            assertEquals(file.getValue().asText(), HexFormat.of().formatHex(digest), file.getKey());
        }
    }

    @Test
    void buyerProvenanceIdentifiesTheDevelopmentOnlyJavaGenerator() throws IOException {
        JsonNode manifest = new ObjectMapper().readTree(Path.of("source-manifest.json").toFile());
        JsonNode generator = manifest.get("buyerGeneration");
        String source = "src/test/java/dev/nklip/javacraft/shardshop/workload/dataset/BuyerDatasetGenerator.java";
        assertEquals(source, generator.get("source").asText());
        assertTrue(manifest.get("retainedFiles").has(source), "Generator implementation is covered by the provenance hash");
        assertEquals(BuyerDatasetGenerator.class.getName(), generator.get("mainClass").asText());
        assertEquals("exec:exec@regenerate-buyers", generator.get("mavenGoal").asText());
        assertEquals("target/generated-datasets/buyers-v1", generator.get("outputDirectory").asText());
        assertFalse(generator.get("networkRequired").asBoolean());
        assertFalse(manifest.get("retainedFiles").has("scripts/generate-buyers.py"));
        assertFalse(Files.exists(Path.of("target/classes/dev/nklip/javacraft/shardshop/workload/dataset/BuyerDatasetGenerator.class")),
                "Development tooling must not enter production classes");
    }

    /** A pool row with this name and email part; Asian rows also have the country of the buyer. */
    private static boolean paired(List<Map<String, String>> pool, String name, String emailPart,
                                  BuyerDataset.BuyerDefinition buyer) {
        return pool.stream().anyMatch(row -> row.get("name").equals(name) && row.get("emailPart").equals(emailPart)
                && (!buyer.region().equals("ASIA") || row.get("countryCode").equals(buyer.address().countryCode())));
    }

    /** The rows of a tab-separated file with a header line. */
    private static List<Map<String, String>> rows(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String[] header = lines.getFirst().split("\t", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            assertEquals(header.length, fields.length, file + " row " + line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < header.length; index++) {
                row.put(header[index], fields[index]);
            }
            rows.add(row);
        }
        return rows;
    }
}
