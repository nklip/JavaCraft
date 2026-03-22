package my.javacraft.elastic.data.csv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import my.javacraft.elastic.data.csv.impl.*;

/**
 * CLI entry point that generates post/vote CSV fixtures.
 *
 * <p>Usage:
 * <pre>{@code
 * # generate all files
 * java my.javacraft.elastic.csv.VoteGeneratorApplication
 *
 * # generate one or more generators
 * java my.javacraft.elastic.csv.VoteGeneratorApplication new rising
 * }</pre>
 */
public final class VoteGeneratorApplication {
    private static final String VOTES_HEADER = "userId,postId,action,date";
    private static final String POSTS_HEADER = "postId,createdAt,author";

    private static final Map<String, GeneratorCase> GENERATORS = new LinkedHashMap<>();

    static {
        GENERATORS.put("best", new GeneratorCase(new BestVotesGenerator(), "votes-best.csv", "posts-best.csv"));
        GENERATORS.put("hot", new GeneratorCase(new HotVotesGenerator(), "votes-hot.csv", "posts-hot.csv"));
        GENERATORS.put("new", new GeneratorCase(new NewVotesGenerator(), "votes-new.csv", "posts-new.csv"));
        GENERATORS.put("rising", new GeneratorCase(new RisingVotesGenerator(), "votes-rising.csv", "posts-rising.csv"));
        GENERATORS.put("top", new GeneratorCase(new TopVotesGenerator(), "votes-top.csv", "posts-top.csv"));
    }

    private VoteGeneratorApplication() {
    }

    public static void main(String[] args) throws IOException {
        Set<String> selectedGenerators = normalizeArgs(args);
        Path outputDirectory = resolveOutputDirectory();

        for (Map.Entry<String, GeneratorCase> entry : GENERATORS.entrySet()) {
            if (!selectedGenerators.isEmpty() && !selectedGenerators.contains(entry.getKey())) {
                continue;
            }

            GeneratorCase generatorCase = entry.getValue();
            generatorCase.generator().generatePostVotesInCsv();

            Path votesFile = outputDirectory.resolve("votes").resolve(generatorCase.votesFileName());
            Path postsFile = outputDirectory.resolve("posts").resolve(generatorCase.postsFileName());
            verifyCsv(votesFile, VOTES_HEADER);
            verifyCsv(postsFile, POSTS_HEADER);

            System.out.println("Generated: " + votesFile);
            System.out.println("Generated: " + postsFile);
        }
    }

    private static Set<String> normalizeArgs(String[] args) {
        if (args == null || args.length == 0) {
            return Set.of();
        }
        Set<String> selected = new java.util.LinkedHashSet<>();
        for (String arg : args) {
            String normalized = arg.toLowerCase(Locale.ROOT).trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (!GENERATORS.containsKey(normalized)) {
                throw new IllegalArgumentException(
                        "Unknown generator: '" + arg + "'. Supported values: " + GENERATORS.keySet()
                );
            }
            selected.add(normalized);
        }
        return selected;
    }

    private static void verifyCsv(Path filePath, String expectedHeader) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IllegalStateException("CSV file was not generated: " + filePath);
        }
        java.util.List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("CSV file is empty: " + filePath);
        }
        if (!expectedHeader.equals(lines.getFirst())) {
            throw new IllegalStateException(
                    "Unexpected CSV header in " + filePath + ". Expected: '" + expectedHeader + "', actual: '" + lines.getFirst() + "'"
            );
        }
    }

    private static Path resolveOutputDirectory() {
        String configuredDirectory = System.getProperty(CsvSupport.OUTPUT_DIRECTORY_PROPERTY);
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            return Path.of(configuredDirectory);
        }

        Path moduleDirectory = Path.of("src", "test", "resources", "data", "csv");
        if (Files.isDirectory(moduleDirectory.getParent())) {
            return moduleDirectory;
        }

        Path rootDirectory = Path.of("elastic", "src", "test", "resources", "data", "csv");
        if (Files.isDirectory(rootDirectory.getParent())) {
            return rootDirectory;
        }

        return Path.of("data", "csv");
    }

    private record GeneratorCase(VoteGenerator generator, String votesFileName, String postsFileName) {
    }
}
