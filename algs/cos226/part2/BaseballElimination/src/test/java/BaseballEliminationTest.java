import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handwritten tests for {@link BaseballElimination}: the exact expected answers for
 * {@code teams4.txt}, the command line client, and the edges of the constructor.
 *
 * <p>{@code BaseballEliminationFilesTest} is the counterpart - it drives every shipped division
 * file against an independent oracle. The split is by kind, not by size: everything here is a
 * fixed, readable expectation, while everything there is generated from the fixtures.
 */
class BaseballEliminationTest {

    private BaseballElimination division;

    @BeforeEach
    void setUp() {
        division = new BaseballElimination("teams4.txt");
    }

    @Test
    void testReadsDivisionData() {
        Assertions.assertEquals(4, division.numberOfTeams());
        Assertions.assertEquals(
                List.of("Atlanta", "Philadelphia", "New_York", "Montreal"),
                toList(division.teams())
        );
        Assertions.assertEquals(83, division.wins("Atlanta"));
        Assertions.assertEquals(79, division.losses("Philadelphia"));
        Assertions.assertEquals(6, division.remaining("New_York"));
        Assertions.assertEquals(2, division.against("Philadelphia", "Montreal"));
    }

    @Test
    void testFindsTrivialAndNonTrivialEliminations() {
        Assertions.assertFalse(division.isEliminated("Atlanta"));
        Assertions.assertNull(division.certificateOfElimination("Atlanta"));
        Assertions.assertFalse(division.isEliminated("New_York"));

        Assertions.assertTrue(division.isEliminated("Montreal"));
        Assertions.assertEquals(
                Set.of("Atlanta"),
                toSet(division.certificateOfElimination("Montreal"))
        );

        Assertions.assertTrue(division.isEliminated("Philadelphia"));
        Assertions.assertEquals(
                Set.of("Atlanta", "New_York"),
                toSet(division.certificateOfElimination("Philadelphia"))
        );
    }

    @Test
    void testRejectsUnknownTeams() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> division.wins("Unknown"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> division.losses("Unknown"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> division.remaining("Unknown"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> division.against("Atlanta", "Unknown"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> division.isEliminated("Unknown"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> division.certificateOfElimination("Unknown")
        );
    }

    /**
     * {@code against} validates both arguments, but the assertions above only ever pass an unknown
     * team as the second one, which leaves the first check unexercised.
     */
    @Test
    void testAgainstRejectsAnUnknownTeamAsEitherArgument() {
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> division.against("Unknown", "Atlanta"));
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> division.against("Unknown", "AlsoUnknown"));
    }

    /**
     * The output is not asserted, only that {@code main} runs to completion. It writes through
     * {@code StdOut}, whose static initializer copies {@code System.out} into a {@code PrintWriter}
     * once and never looks again, so redirecting the stream only works if it happens before that
     * class is first loaded - not something a test should depend on in a shared JVM.
     * {@code PercolationStats} is the exception elsewhere in these modules, because it prints with
     * {@code System.out.println} directly.
     *
     * <p>These fixtures between them still run every branch of {@code main}: {@code teams4.txt} has
     * both eliminated and surviving teams, {@code teams1.txt} has a lone survivor, and
     * {@code teams12-allgames.txt} is a finished season where certificates dominate.
     */
    @ParameterizedTest(name = "main runs over {0}")
    @ValueSource(strings = {"teams4.txt", "teams1.txt", "teams12-allgames.txt", "teams5.txt"})
    void testMainRunsToCompletionForAFixture(String fixture) {
        Assertions.assertDoesNotThrow(() -> BaseballElimination.main(new String[]{fixture}));
    }

    /** No arguments falls back to the built-in {@code teams4.txt}; the check is also null-safe. */
    @Test
    void testMainFallsBackToItsDefaultFixture() {
        Assertions.assertDoesNotThrow(() -> BaseballElimination.main(new String[0]));
        Assertions.assertDoesNotThrow(() -> BaseballElimination.main(null));
    }

    @Test
    void testMainRejectsAFileThatDoesNotExist() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BaseballElimination.main(new String[]{"no-such-division.txt"})
        );
    }

    /**
     * The constructor guards its first read with {@code hasNextLine}, so a file with no content at
     * all yields an empty division rather than failing. Nothing shipped is empty, so it is written
     * here - and a header with no team lines behind it has to behave the same way.
     */
    @Test
    void testAFileWithNoTeamsYieldsAnEmptyDivision(@TempDir Path directory) throws IOException {
        Path empty = Files.writeString(directory.resolve("empty.txt"), "");
        Path headerOnly = Files.writeString(directory.resolve("header-only.txt"), "4\n");

        for (Path file : List.of(empty, headerOnly)) {
            BaseballElimination parsed = new BaseballElimination(file.toAbsolutePath().toString());

            Assertions.assertEquals(0, parsed.numberOfTeams(), file.getFileName().toString());
            Assertions.assertFalse(parsed.teams().iterator().hasNext(), file.getFileName().toString());
            Assertions.assertThrows(IllegalArgumentException.class, () -> parsed.wins("Atlanta"));
        }
    }

    private static List<String> toList(Iterable<String> values) {
        List<String> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    private static Set<String> toSet(Iterable<String> values) {
        Set<String> result = new HashSet<>();
        values.forEach(result::add);
        return result;
    }
}
