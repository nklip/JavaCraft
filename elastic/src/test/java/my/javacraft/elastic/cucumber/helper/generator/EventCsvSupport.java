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

public final class EventCsvSupport {
    public static final int USERS_PER_POST = 100;

    private static final String CSV_HEADER = "userId,postId,action,date";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final String OUTPUT_DIRECTORY_PROPERTY = "elastic.events.csv.output";

    private EventCsvSupport() {
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

    public static void writeCsv(String fileName, List<String> lines) {
        Path outputDirectory = resolveOutputDirectory();
        Path outputFile = outputDirectory.resolve(fileName);

        try {
            Files.createDirectories(outputDirectory);
            try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                writer.write(CSV_HEADER);
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
