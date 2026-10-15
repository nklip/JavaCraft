package dev.nklip.javacraft.shardshop.workload.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuyerDatasetGeneratorTest {
    private static final List<String> REGIONS = List.of("US", "EU", "ASIA");
    @TempDir
    Path module;

    @Test
    void reproducesEveryFrozenBuyerByteAndDoesNotChangeInputs() throws IOException {
        copySources();
        Path given = module.resolve("sources/names/US-given.tsv");
        byte[] original = Files.readAllBytes(given);

        Path output = BuyerDatasetGenerator.generate(module);

        assertEquals(module.resolve("target/generated-datasets/buyers-v1"), output);
        for (String region : REGIONS) {
            assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/datasets/buyers-v1/" + region + ".tsv")),
                    Files.readAllBytes(output.resolve(region + ".tsv")), region + " matches the original Python output byte for byte");
            assertEquals(1001, Files.readAllLines(output.resolve(region + ".tsv")).size());
        }
        assertArrayEquals(original, Files.readAllBytes(given));
        assertFalse(Files.exists(module.resolve("src")), "Generation writes only target, never source fixtures");
    }

    @Test
    void repeatedGenerationKeepsUnicodeAndAsciiNumbersRegardlessOfDefaultLocale() throws IOException {
        copySources();
        Path output = BuyerDatasetGenerator.generate(module);
        List<byte[]> first = REGIONS.stream().map(region -> readBytes(output.resolve(region + ".tsv"))).toList();
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            BuyerDatasetGenerator.generate(module);
        } finally {
            Locale.setDefault(previous);
        }
        for (int index = 0; index < REGIONS.size(); index++) {
            assertArrayEquals(first.get(index), Files.readAllBytes(output.resolve(REGIONS.get(index) + ".tsv")));
        }
    }

    @Test
    void rejectsMissingSourceBeforeWritingOutput() throws IOException {
        copySources();
        Files.delete(module.resolve("sources/names/ASIA-surname.tsv"));
        assertThrows(IOException.class, () -> BuyerDatasetGenerator.generate(module));
        assertFalse(Files.exists(module.resolve("target")));
    }

    @Test
    void rejectsEmptyRegionalPool() throws IOException {
        copySources();
        Path file = module.resolve("sources/names/EU-given.tsv");
        Files.writeString(file, Files.readAllLines(file).getFirst() + "\n");
        assertInvalid("regional name pools must not be empty");
    }

    @Test
    void rejectsMissingAsianCountryPool() throws IOException {
        copySources();
        Path file = module.resolve("sources/names/ASIA-surname.tsv");
        Files.write(file, Files.readAllLines(file).stream().filter(line -> !line.contains("\tVN\t")).toList());
        assertInvalid("every selected country needs given names and surnames: VN");
    }

    @Test
    void reportsUniqueEmailExhaustionInsteadOfAddingSuffixesOrLooping() throws IOException {
        copySources();
        for (String kind : List.of("given", "surname")) {
            Path file = module.resolve("sources/names/US-" + kind + ".tsv");
            Files.write(file, Files.readAllLines(file).subList(0, 2));
        }
        assertInvalid("name pool cannot provide another unique email at row 4");
    }

    @Test
    void rejectsUnexpectedHeadersAndMalformedRows() throws IOException {
        copySources();
        Path file = module.resolve("sources/names/US-given.tsv");
        List<String> original = Files.readAllLines(file);
        Files.writeString(file, "name\nexample\n");
        assertInvalid("unexpected TSV header");
        Files.writeString(file, original.getFirst() + "\nonly-one-column\n");
        assertInvalid("malformed unquoted TSV row 2");
        Files.writeString(file, original.getFirst() + "\n\"Jane\"\tjane\tUS\t1\t10\ttest\n");
        assertInvalid("malformed unquoted TSV row 2");
    }

    @Test
    void rejectsBlankNamesInvalidEmailPartsAndForeignCountryCodes() throws IOException {
        copySources();
        Path file = module.resolve("sources/names/US-given.tsv");
        String header = Files.readAllLines(file).getFirst() + "\n";
        Files.writeString(file, header + "\tjane\tUS\t1\t10\ttest\n");
        assertInvalid("name must not be blank");
        Files.writeString(file, header + "Jane\tJäne\tUS\t1\t10\ttest\n");
        assertInvalid("emailPart must contain lowercase ASCII letters or digits");
        Files.writeString(file, header + "Jane\tjane\tJP\t1\t10\ttest\n");
        assertInvalid("country outside US");
    }

    @Test
    void rejectsMissingDuplicateOrBlankAddresses() throws IOException {
        copySources();
        Path file = module.resolve("sources/address-patterns.tsv");
        List<String> original = Files.readAllLines(file);
        Files.write(file, original.subList(0, original.size() - 1));
        assertInvalid("address countries must cover");
        Files.writeString(file, String.join("\n", original) + "\n" + original.get(1) + "\n");
        assertInvalid("duplicate country US");
        Files.writeString(file, original.getFirst() + "\nUS\t\tWashington\t20001\n");
        assertInvalid("street must not be blank");
    }

    @Test
    void refusesOutputDirectoryAndFileSymlinksToProtectSourceFixtures() throws IOException {
        copySources();
        Files.createSymbolicLink(module.resolve("target"), module.resolve("sources"));
        assertThrows(IOException.class, () -> BuyerDatasetGenerator.generate(module));
        Files.delete(module.resolve("target"));
        Path output = module.resolve("target/generated-datasets/buyers-v1");
        Files.createDirectories(output);
        Path source = module.resolve("sources/names/US-given.tsv");
        byte[] original = Files.readAllBytes(source);
        Files.createSymbolicLink(output.resolve("US.tsv"), source);
        assertThrows(IOException.class, () -> BuyerDatasetGenerator.generate(module));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    void requiresExactlyTheModuleDirectoryArgument() {
        assertThrows(IllegalArgumentException.class, () -> BuyerDatasetGenerator.main(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> BuyerDatasetGenerator.main(new String[]{".", "src/main/resources"}));
    }

    private void assertInvalid(String expectedMessage) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BuyerDatasetGenerator.generate(module));
        assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
        assertFalse(Files.exists(module.resolve("target")), "Invalid inputs must not produce partial output");
    }

    private void copySources() throws IOException {
        Files.createDirectories(module.resolve("sources/names"));
        Files.copy(Path.of("sources/address-patterns.tsv"), module.resolve("sources/address-patterns.tsv"));
        for (String region : REGIONS) {
            for (String kind : List.of("given", "surname")) {
                Path file = Path.of("sources/names/" + region + "-" + kind + ".tsv");
                Files.copy(file, module.resolve(file));
            }
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
