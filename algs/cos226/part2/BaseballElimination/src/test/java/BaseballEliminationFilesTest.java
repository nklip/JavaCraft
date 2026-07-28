import java.util.ArrayList;
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

/**
 * Drives {@link BaseballElimination} from the division files in {@code src/test/resources}.
 *
 * <p>The oracle is the certificate condition itself. A team {@code x} is mathematically eliminated
 * exactly when some non-empty subset {@code R} of the other teams satisfies
 *
 * <pre>    w(R) + g(R) &gt; (w[x] + r[x]) * |R|</pre>
 *
 * where {@code w(R)} is the total wins of the teams in {@code R} and {@code g(R)} the games still
 * to be played among them: the teams in {@code R} must between them win at least {@code g(R)} more
 * games, so one of them finishes above what {@code x} can reach. That inequality needs no maxflow
 * to evaluate, which is what makes it usable as an independent check on an implementation built
 * out of {@code FlowNetwork} and {@code FordFulkerson}.
 *
 * <p>It is used two ways. On the fixtures with at most twelve teams every subset is enumerated,
 * which gives exact ground truth for {@code isEliminated} - the strongest form of the check, and
 * the reason the small fixtures matter more than their size suggests. On every fixture, including
 * the sixty-team ones where enumeration is out of the question, the returned certificate is
 * verified against the same inequality. That is a soundness check rather than a completeness one:
 * it proves a reported elimination is real, but not that a team reported safe truly is.
 *
 * <p>The specification does not require any particular certificate, only a valid one, so these
 * tests verify the subset rather than compare it to an expected answer. {@code BaseballEliminationTest}
 * keeps the exact expected subsets for {@code teams4.txt} as a regression anchor.
 */
class BaseballEliminationFilesTest {

    /** Above this, enumerating every subset stops being affordable. */
    private static final int EXHAUSTIVE_LIMIT = 12;

    /** Fixtures small enough for the exhaustive subset search, with their team count. */
    static Stream<Arguments> smallFixtures() {
        return Stream.of(
                Arguments.of("teams1.txt", 1),
                Arguments.of("teams4.txt", 4),
                Arguments.of("teams4a.txt", 4),
                Arguments.of("teams4b.txt", 4),
                Arguments.of("teams5.txt", 5),
                Arguments.of("teams5a.txt", 5),
                Arguments.of("teams5b.txt", 5),
                Arguments.of("teams5c.txt", 5),
                Arguments.of("teams7.txt", 7),
                Arguments.of("teams8.txt", 8),
                Arguments.of("teams10.txt", 10),
                Arguments.of("teams12.txt", 12),
                Arguments.of("teams12-allgames.txt", 12)
        );
    }

    /** The rest, where only the certificate can be checked. */
    static Stream<Arguments> largeFixtures() {
        return Stream.of(
                Arguments.of("teams24.txt", 24),
                Arguments.of("teams29.txt", 29),
                Arguments.of("teams30.txt", 30),
                Arguments.of("teams32.txt", 32),
                Arguments.of("teams36.txt", 36),
                Arguments.of("teams42.txt", 42),
                Arguments.of("teams48.txt", 48),
                Arguments.of("teams50.txt", 50),
                Arguments.of("teams54.txt", 54),
                Arguments.of("teams60.txt", 60)
        );
    }

    static Stream<Arguments> allFixtures() {
        return Stream.concat(smallFixtures(), largeFixtures());
    }

    @ParameterizedTest(name = "{0} holds {1} teams")
    @MethodSource("allFixtures")
    void testReadsTheTeamCountAndNamesFromTheFile(String fixture, int expectedTeams) {
        Division division = Division.read(fixture);
        BaseballElimination elimination = new BaseballElimination(fixture);

        Assertions.assertEquals(expectedTeams, division.names.length, fixture + ": fixture changed");
        Assertions.assertEquals(expectedTeams, elimination.numberOfTeams(), fixture);
        Assertions.assertEquals(
                List.of(division.names),
                toList(elimination.teams()),
                fixture + ": teams are listed in file order"
        );
    }

    @ParameterizedTest(name = "{0}: wins, losses, remaining and against match the file")
    @MethodSource("allFixtures")
    void testAccessorsMatchTheFileContents(String fixture, int ignoredTeams) {
        Division division = Division.read(fixture);
        BaseballElimination elimination = new BaseballElimination(fixture);

        for (int i = 0; i < division.names.length; i++) {
            String team = division.names[i];
            int index = i;
            Assertions.assertEquals(division.wins[i], elimination.wins(team), () -> fixture + ": wins(" + team + ")");
            Assertions.assertEquals(division.losses[i], elimination.losses(team), () -> fixture + ": losses(" + team + ")");
            Assertions.assertEquals(division.remaining[i], elimination.remaining(team), () -> fixture + ": remaining(" + team + ")");

            for (int j = 0; j < division.names.length; j++) {
                String other = division.names[j];
                int column = j;
                Assertions.assertEquals(
                        division.games[i][j],
                        elimination.against(team, other),
                        () -> fixture + ": against(" + team + ", " + other + ") at [" + index + "][" + column + "]"
                );
            }
        }
    }

    /**
     * The strongest check available: every subset of the other teams is tried, so this decides
     * elimination exactly rather than merely confirming a reported one.
     */
    @ParameterizedTest(name = "{0}: elimination matches the exhaustive subset search")
    @MethodSource("smallFixtures")
    void testEliminationMatchesTheExhaustiveSubsetSearch(String fixture, int ignoredTeams) {
        Division division = Division.read(fixture);
        Assertions.assertTrue(division.names.length <= EXHAUSTIVE_LIMIT, fixture + ": too big to enumerate");
        BaseballElimination elimination = new BaseballElimination(fixture);

        for (int x = 0; x < division.names.length; x++) {
            String team = division.names[x];
            List<String> witness = division.searchEverySubsetForACertificate(x);

            Assertions.assertEquals(
                    witness != null,
                    elimination.isEliminated(team),
                    () -> fixture + ": " + team + (witness == null
                            ? " is not eliminated by any subset"
                            : " is eliminated by " + witness)
            );
        }
    }

    /**
     * Soundness on every fixture, including the ones too large to enumerate: whatever subset is
     * returned has to satisfy the certificate inequality.
     */
    @ParameterizedTest(name = "{0}: every returned certificate is valid")
    @MethodSource("allFixtures")
    void testEveryReturnedCertificateSatisfiesTheEliminationInequality(String fixture, int ignoredTeams) {
        Division division = Division.read(fixture);
        BaseballElimination elimination = new BaseballElimination(fixture);
        Map<String, Integer> indexOf = division.indexByName();

        for (String team : division.names) {
            Iterable<String> certificate = elimination.certificateOfElimination(team);
            if (certificate == null) {
                continue;
            }

            List<String> subset = toList(certificate);
            Assertions.assertFalse(subset.isEmpty(), () -> fixture + ": empty certificate for " + team);
            Assertions.assertFalse(subset.contains(team), () -> fixture + ": " + team + " certifies itself");
            Assertions.assertEquals(
                    subset.size(), new HashSet<>(subset).size(),
                    () -> fixture + ": duplicate teams in the certificate for " + team
            );
            subset.forEach(member -> Assertions.assertTrue(
                    indexOf.containsKey(member), () -> fixture + ": unknown team " + member + " in a certificate));"
            ));

            Assertions.assertTrue(
                    division.isAValidCertificate(indexOf.get(team), subset, indexOf),
                    () -> fixture + ": " + subset + " does not actually eliminate " + team
            );
        }
    }

    @ParameterizedTest(name = "{0}: isEliminated agrees with certificateOfElimination")
    @MethodSource("allFixtures")
    void testACertificateIsReturnedExactlyWhenTheTeamIsEliminated(String fixture, int ignoredTeams) {
        Division division = Division.read(fixture);
        BaseballElimination elimination = new BaseballElimination(fixture);

        for (String team : division.names) {
            Assertions.assertEquals(
                    elimination.certificateOfElimination(team) != null,
                    elimination.isEliminated(team),
                    () -> fixture + ": the two disagree for " + team
            );
        }
    }

    /**
     * Trivial elimination is the single-team case of the same inequality: if some team already has
     * more wins than {@code x} can reach, {@code {that team}} is itself a certificate.
     */
    @ParameterizedTest(name = "{0}: teams that cannot catch the leader are eliminated")
    @MethodSource("allFixtures")
    void testTriviallyEliminatedTeamsAreReported(String fixture, int ignoredTeams) {
        Division division = Division.read(fixture);
        BaseballElimination elimination = new BaseballElimination(fixture);

        for (int x = 0; x < division.names.length; x++) {
            int best = division.wins[x] + division.remaining[x];
            for (int other = 0; other < division.names.length; other++) {
                if (other != x && division.wins[other] > best) {
                    String team = division.names[x];
                    Assertions.assertTrue(
                            elimination.isEliminated(team),
                            () -> fixture + ": " + team + " cannot pass " + division.names[0]
                                    + " yet is reported safe"
                    );
                    break;
                }
            }
        }
    }

    /** Some team always finishes on top, so a division can never eliminate everyone. */
    @ParameterizedTest(name = "{0}: at least one team survives")
    @MethodSource("allFixtures")
    void testNotEveryTeamCanBeEliminated(String fixture, int ignoredTeams) {
        Division division = Division.read(fixture);
        BaseballElimination elimination = new BaseballElimination(fixture);

        long eliminated = Stream.of(division.names).filter(elimination::isEliminated).count();

        Assertions.assertTrue(
                eliminated < division.names.length,
                () -> fixture + ": all " + division.names.length + " teams reported eliminated"
        );
    }

    @ParameterizedTest(name = "{0}: against is symmetric and zero on the diagonal")
    @MethodSource("allFixtures")
    void testAgainstIsSymmetricAndZeroOnTheDiagonal(String fixture, int ignoredTeams) {
        Division division = Division.read(fixture);
        BaseballElimination elimination = new BaseballElimination(fixture);

        for (String team : division.names) {
            Assertions.assertEquals(0, elimination.against(team, team), () -> fixture + ": " + team + " plays itself");
            for (String other : division.names) {
                Assertions.assertEquals(
                        elimination.against(team, other),
                        elimination.against(other, team),
                        () -> fixture + ": against is asymmetric for " + team + " and " + other
                );
            }
        }
    }

    /**
     * In most fixtures a team's remaining games are exactly the games left inside the division, so
     * the row of {@code against} sums to {@code remaining}. Three files deliberately break that -
     * their teams also have games against opponents outside the division - which is worth pinning
     * down, because it is the case where the flow network's source capacities and a team's
     * {@code remaining} do not agree.
     */
    @ParameterizedTest(name = "{0}: remaining games versus the in-division row sum")
    @MethodSource("allFixtures")
    void testRemainingGamesAgreeWithTheAgainstRowExceptWhereTheFixtureSaysOtherwise(
            String fixture, int ignoredTeams) {
        Set<String> playOutsideTheDivision = Set.of("teams5.txt", "teams5c.txt", "teams50.txt");
        Division division = Division.read(fixture);
        BaseballElimination elimination = new BaseballElimination(fixture);

        int mismatches = 0;
        for (int i = 0; i < division.names.length; i++) {
            int rowSum = 0;
            for (int j = 0; j < division.names.length; j++) {
                rowSum += elimination.against(division.names[i], division.names[j]);
            }
            if (rowSum != elimination.remaining(division.names[i])) {
                mismatches++;
            }
        }

        if (playOutsideTheDivision.contains(fixture)) {
            Assertions.assertTrue(mismatches > 0, fixture + ": expected games outside the division");
        } else {
            Assertions.assertEquals(0, mismatches, fixture + ": remaining should be the row sum");
        }
    }

    /** A single-team division has nobody to be eliminated by. */
    @Test
    void testTheOneTeamDivisionHasNoEliminations() {
        BaseballElimination elimination = new BaseballElimination("teams1.txt");

        Assertions.assertEquals(1, elimination.numberOfTeams());
        Assertions.assertEquals(List.of("Turing"), toList(elimination.teams()));
        Assertions.assertFalse(elimination.isEliminated("Turing"));
        Assertions.assertNull(elimination.certificateOfElimination("Turing"));
        Assertions.assertEquals(0, elimination.against("Turing", "Turing"));
    }

    /**
     * {@code teams12-allgames.txt} is the degenerate end of the range: all games have already been
     * played, so every team has zero remaining and the grid is entirely zero. The flow network then
     * has no game vertices and every source edge has zero capacity, which makes it the fixture most
     * likely to expose an empty-network mistake.
     *
     * <p>With nothing left to play, elimination is decided by the standings alone: exactly the
     * teams tied for the most wins survive.
     */
    @Test
    void testTheFinishedSeasonLeavesOnlyTheTeamsTiedForTheMostWins() {
        Division division = Division.read("teams12-allgames.txt");
        BaseballElimination elimination = new BaseballElimination("teams12-allgames.txt");

        int mostWins = 0;
        for (int i = 0; i < division.names.length; i++) {
            Assertions.assertEquals(0, division.remaining[i], division.names[i] + " has games left");
            for (int j = 0; j < division.names.length; j++) {
                Assertions.assertEquals(0, division.games[i][j], "the season is over");
            }
            mostWins = Math.max(mostWins, division.wins[i]);
        }

        Set<String> survivors = new HashSet<>();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < division.names.length; i++) {
            if (!elimination.isEliminated(division.names[i])) {
                survivors.add(division.names[i]);
            }
            if (division.wins[i] == mostWins) {
                expected.add(division.names[i]);
            }
        }

        Assertions.assertEquals(expected, survivors);
        Assertions.assertEquals(10, division.names.length - survivors.size(), "ten eliminated");
    }

    private static List<String> toList(Iterable<String> values) {
        List<String> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    /**
     * A division file, parsed here rather than through the class under test.
     */
    private record Division(String[] names, int[] wins, int[] losses, int[] remaining, int[][] games) {

        static Division read(String fixture) {
            In in = ResourceFiles.open(Division.class, fixture);
            int count = Integer.parseInt(in.readLine().trim());
            String[] names = new String[count];
            int[] wins = new int[count];
            int[] losses = new int[count];
            int[] remaining = new int[count];
            int[][] games = new int[count][count];

            for (int i = 0; i < count; i++) {
                String[] fields = in.readLine().trim().split("\\s+");
                names[i] = fields[0];
                wins[i] = Integer.parseInt(fields[1]);
                losses[i] = Integer.parseInt(fields[2]);
                remaining[i] = Integer.parseInt(fields[3]);
                for (int j = 0; j < count; j++) {
                    games[i][j] = Integer.parseInt(fields[4 + j]);
                }
            }
            in.close();
            return new Division(names, wins, losses, remaining, games);
        }

        Map<String, Integer> indexByName() {
            Map<String, Integer> indexOf = new HashMap<>();
            for (int i = 0; i < names.length; i++) {
                indexOf.put(names[i], i);
            }
            return indexOf;
        }

        /**
         * @return a subset that eliminates team {@code x}, or {@code null} if no subset does. Every subset of the other
         * teams is tried, so a {@code null} here is proof.
         */
        List<String> searchEverySubsetForACertificate(int x) {
            long capacity = (long) wins[x] + remaining[x];
            for (int mask = 1; mask < (1 << names.length); mask++) {
                if ((mask & (1 << x)) != 0) {
                    continue;
                }
                long total = 0;
                int size = 0;
                for (int i = 0; i < names.length; i++) {
                    if ((mask & (1 << i)) == 0) {
                        continue;
                    }
                    total += wins[i];
                    size++;
                    for (int j = i + 1; j < names.length; j++) {
                        if ((mask & (1 << j)) != 0) {
                            total += games[i][j];
                        }
                    }
                }
                if (total > capacity * size) {
                    List<String> subset = new ArrayList<>();
                    for (int i = 0; i < names.length; i++) {
                        if ((mask & (1 << i)) != 0) {
                            subset.add(names[i]);
                        }
                    }
                    return subset;
                }
            }
            return null;
        }

        /**
         * Checks {@code w(R) + g(R) > (w[x] + r[x]) * |R|} for the given subset.
         */
        boolean isAValidCertificate(int x, List<String> subset, Map<String, Integer> indexOf) {
            long total = 0;
            List<Integer> members = subset.stream().map(indexOf::get).toList();
            for (int position = 0; position < members.size(); position++) {
                int i = members.get(position);
                total += wins[i];
                for (int other = position + 1; other < members.size(); other++) {
                    total += games[i][members.get(other)];
                }
            }
            return total > ((long) wins[x] + remaining[x]) * members.size();
        }
    }
}
