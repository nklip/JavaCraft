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
import my.javacraft.elastic.cucumber.helper.generator.impl.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VoteGeneratorTest {

    Path outputDirectory = Path.of("src/test/resources/data/csv");

    /**
     * Enable this test to generate a new set of inputs in data/csv folder
     */
    @Disabled
    @ParameterizedTest
    @MethodSource("generatorCases")
    void generateEventsInCsvShouldGenerateExpectedFile(
            String fileName,
            VoteGenerator generator,
            int firstPost,
            int lastPost,
            int maxAgeDays,
            int usersPerPost
    ) throws IOException {
        Instant beforeGeneration = Instant.now();

        generator.generatePostVotesInCsv();

        Instant afterGeneration = Instant.now();
        Path generatedCsv = outputDirectory.resolve("votes").resolve(fileName);
        Assertions.assertTrue(Files.exists(generatedCsv), "CSV file must be generated: " + fileName);

        int postCount    = lastPost - firstPost + 1;
        int expectedRows = usersPerPost * postCount;

        List<String> lines = Files.readAllLines(generatedCsv, StandardCharsets.UTF_8);
        Assertions.assertEquals(expectedRows + 1, lines.size(),
                "Expected %d events + header".formatted(expectedRows));
        Assertions.assertEquals("userId,postId,action,date", lines.getFirst());

        Set<String> uniqueRows = new HashSet<>();
        Map<Integer, Set<Integer>> usersByPost = new HashMap<>();

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] values = line.split(",", -1);
            Assertions.assertEquals(4, values.length, "Invalid CSV row: " + line);

            int userId = parseSuffix(values[0], "user-", 1, usersPerPost);
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

        Assertions.assertEquals(expectedRows, uniqueRows.size(), "Each (user,post) pair must be unique");
        Assertions.assertEquals(postCount, usersByPost.size(), "Expected %d generated posts".formatted(postCount));
        for (int postId = firstPost; postId <= lastPost; postId++) {
            Set<Integer> users = usersByPost.get(postId);
            Assertions.assertNotNull(users, "Missing generated post: post-%02d".formatted(postId));
            Assertions.assertEquals(usersPerPost, users.size(),
                    "Each post must have %d user events".formatted(usersPerPost));
        }
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
                // BestVotesGenerator: 31-364 days old, 100 users/post → maxAgeDays=364
                Arguments.of("votes-best.csv",   new BestVotesGenerator(),    1,  10, 364, 100),
                // HotVotesGenerator: all events within 60 min, 100 users/post → maxAgeDays=1
                Arguments.of("votes-hot.csv",    new HotVotesGenerator(),    11,  20,   1, 100),
                // NewVotesGenerator: ~1-2 minutes old, up to 10 users/post → maxAgeDays=1
                Arguments.of("votes-new.csv",    new NewVotesGenerator(),    21,  30,   1,  10),
                // RisingVotesGenerator: ~2 minutes old, 30 users/post → maxAgeDays=1
                Arguments.of("votes-rising.csv", new RisingVotesGenerator(), 31,  40,   1,  30),
                // TopVotesGenerator: 366-730 days old, 500 users/post → maxAgeDays=731
                // 500 users compensate for the lower 61-70% upvote ratio, pushing karma (110-200) above
                // BestVotesGenerator's ceiling (80) so posts 41-50 still win Top ALL.
                Arguments.of("votes-top.csv",    new TopVotesGenerator(),    41,  50, 731, 500)
        );
    }
}
