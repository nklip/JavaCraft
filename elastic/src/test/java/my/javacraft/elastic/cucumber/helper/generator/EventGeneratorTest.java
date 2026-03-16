package my.javacraft.elastic.cucumber.helper.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EventGeneratorTest {

    Path outputDirectory = Path.of("src/test/resources/data/csv");

    @ParameterizedTest
    @MethodSource("generatorCases")
    void generateEventsInCsvShouldGenerateExpectedFile(
            String fileName,
            EventGenerator generator,
            int firstPost,
            int lastPost,
            int maxAgeDays
    ) throws IOException {
        Instant beforeGeneration = Instant.now();

        generator.generateEventsInCsv();

        Instant afterGeneration = Instant.now();
        Path generatedCsv = outputDirectory.resolve(fileName);
        Assertions.assertTrue(Files.exists(generatedCsv), "CSV file must be generated: " + fileName);

        List<String> lines = Files.readAllLines(generatedCsv, StandardCharsets.UTF_8);
        Assertions.assertEquals(3001, lines.size(), "Expected 3000 events + header");
        Assertions.assertEquals("userId,postId,action,date", lines.getFirst());

        Set<String> uniqueRows = new HashSet<>();
        Map<Integer, Set<Integer>> usersByPost = new HashMap<>();

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] values = line.split(",", -1);
            Assertions.assertEquals(4, values.length, "Invalid CSV row: " + line);

            int userId = parseSuffix(values[0], "user-", 1, 300);
            int postId = parseSuffix(values[1], "post-", firstPost, lastPost);
            Assertions.assertTrue(values[2].equals("UPVOTE") || values[2].equals("DOWNVOTE"),
                    "Unexpected action: " + values[2]);

            Instant eventDate = Instant.parse(values[3]);
            Assertions.assertFalse(eventDate.isAfter(afterGeneration.plusSeconds(1)));
            Assertions.assertFalse(
                    eventDate.isBefore(beforeGeneration.minus(Duration.ofDays(maxAgeDays)).minusSeconds(1)),
                    "Date is outside expected age window for " + fileName + ": " + values[3]
            );

            uniqueRows.add(values[0] + "|" + values[1]);
            usersByPost.computeIfAbsent(postId, key -> new HashSet<>()).add(userId);
        }

        Assertions.assertEquals(3000, uniqueRows.size(), "Each (user,post) pair must be unique");
        Assertions.assertEquals(10, usersByPost.size(), "Expected 10 generated posts");
        for (int postId = firstPost; postId <= lastPost; postId++) {
            Set<Integer> users = usersByPost.get(postId);
            Assertions.assertNotNull(users, "Missing generated post: post-%02d".formatted(postId));
            Assertions.assertEquals(300, users.size(), "Each post must have 300 user events");
        }
    }

    @Test
    void risingEventsShouldContainBothOldAndRecentVotes() throws IOException {
        Instant now = Instant.now();

        new RisingEvents().generateEventsInCsv();

        Path generatedCsv = outputDirectory.resolve("events-rising.csv");
        List<String> lines = Files.readAllLines(generatedCsv, StandardCharsets.UTF_8);

        boolean foundOldEvent = false;
        boolean foundRecentEvent = false;
        for (int i = 1; i < lines.size(); i++) {
            String[] values = lines.get(i).split(",", -1);
            Instant eventDate = Instant.parse(values[3]);
            long ageDays = Duration.between(eventDate, now).toDays();
            if (ageDays >= 100) {
                foundOldEvent = true;
            }
            if (ageDays <= 7) {
                foundRecentEvent = true;
            }
        }

        Assertions.assertTrue(foundOldEvent, "Rising file should include old baseline events");
        Assertions.assertTrue(foundRecentEvent, "Rising file should include recent acceleration events");
    }

    private static int parseSuffix(String value, String prefix, int min, int max) {
        Assertions.assertTrue(value.startsWith(prefix), "Value should start with '" + prefix + "': " + value);
        int parsedValue = Integer.parseInt(value.substring(prefix.length()));
        Assertions.assertTrue(parsedValue >= min && parsedValue <= max,
                "Value is outside expected range: " + value);
        return parsedValue;
    }

    private static Stream<Arguments> generatorCases() {
        return Stream.of(
                Arguments.of("events-best.csv", new BestEvents(), 1, 10, 180),
                Arguments.of("events-hot.csv", new HotEvents(), 11, 20, 7),
                Arguments.of("events-new.csv", new NewEvents(), 21, 30, 1),
                Arguments.of("events-rising.csv", new RisingEvents(), 31, 40, 180),
                Arguments.of("events-top.csv", new TopEvents(), 41, 50, 365)
        );
    }
}
