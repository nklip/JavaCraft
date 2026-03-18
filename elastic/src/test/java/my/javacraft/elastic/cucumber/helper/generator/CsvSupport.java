package my.javacraft.elastic.cucumber.helper.generator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class CsvSupport {
    public static final int USERS_PER_POST = 100;

    private static final String CSV_HEADER = "userId,postId,action,date";
    private static final String POSTS_CSV_HEADER = "postId,createdAt";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    public static final String OUTPUT_DIRECTORY_PROPERTY = "elastic.events.csv.output";

    private CsvSupport() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    public static boolean isUpvote(int userId, int postId, int upvotePercent) {
        int normalizedPercent = Math.max(0, Math.min(100, upvotePercent));
        int bucket = Math.floorMod(userId * 31 + postId * 17, 100);
        return bucket < normalizedPercent;
    }

    public static String csvLine(int userId, int postId, boolean upvote, Instant eventTime) {
        String action = upvote ? "UPVOTE" : "DOWNVOTE";
        return "user-%03d,post-%02d,%s,%s".formatted(
                userId,
                postId,
                action,
                TIMESTAMP_FORMATTER.format(eventTime)
        );
    }

    public static String postCsvLine(int postId, Instant createdAt) {
        return "post-%02d,%s".formatted(postId, TIMESTAMP_FORMATTER.format(createdAt));
    }

    /** Writes vote rows to {@code {outputDir}/votes/{fileName}}. */
    public static void writeVotesCsv(String fileName, List<String> lines) {
        writeFile("votes", fileName, CSV_HEADER, lines);
    }

    /** Writes post-metadata rows to {@code {outputDir}/posts/{fileName}}. */
    public static void writePostsCsv(String fileName, List<String> lines) {
        writeFile("posts", fileName, POSTS_CSV_HEADER, lines);
    }

    private static void writeFile(String subDir, String fileName, String header, List<String> lines) {
        Path outputDirectory = resolveOutputDirectory().resolve(subDir);
        Path outputFile = outputDirectory.resolve(fileName);

        try {
            Files.createDirectories(outputDirectory);
            try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                writer.write(header);
                writer.newLine();
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CSV file: " + outputFile, e);
        }
    }

    private static Path resolveOutputDirectory() {
        String configuredDirectory = System.getProperty(OUTPUT_DIRECTORY_PROPERTY);
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
}
