import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives {@link BoggleSolver} from the board and dictionary files in {@code src/test/resources}.
 *
 * <p>Two independent checks run over every board. The first is the file names: each
 * {@code board-pointsN.txt} is named for the total score its words are worth, which holds exactly
 * against {@code dictionary-yawl.txt} and nothing else - {@code twl06} and {@code enable2k} score
 * those boards lower, {@code sowpods} higher.
 *
 * <p>The second is a second solver, written out in {@link #solveIndependently}. It is an ordinary
 * depth-first search over a {@link HashSet} of words and a {@link HashSet} of their prefixes, so it
 * shares no data structure with the {@code TST} under test, and its results are compared for exact
 * set equality rather than for containment. Enumerating prefixes of a quarter of a million words
 * would be far too slow, so the dictionary is first cut down to the words spellable from the
 * letters actually on the board; that leaves the answer unchanged and brings even the six-by-six
 * board down to about forty milliseconds.
 *
 * <p>{@code board-aqua.txt} and {@code board-qwerty.txt} are traps rather than examples: both
 * namesakes are in the dictionary, and neither can be formed. They are covered separately, because
 * that is the whole point of those two fixtures.
 *
 * <p>Solving a board is deterministic, so the results are memoised across tests - several tests
 * look at the same board and the dictionary is large enough that re-solving would dominate the
 * suite.
 */
class BoggleSolverFilesTest {

    private static final String YAWL = "dictionary-yawl.txt";

    private static String[] yawlWords;
    private static BoggleSolver yawlSolver;

    private static final Map<String, Set<String>> SOLVED = new HashMap<>();

    @BeforeAll
    static void buildTheDictionaryOnce() {
        yawlWords = ResourceFiles.open(BoggleSolverFilesTest.class, YAWL).readAllStrings();
        yawlSolver = new BoggleSolver(yawlWords);
    }

    /** Boards named for the score their words are worth under {@code dictionary-yawl.txt}. */
    static Stream<Arguments> scoredBoards() {
        return Stream.of(
                Arguments.of("board-points0.txt", 0),
                Arguments.of("board-points1.txt", 1),
                Arguments.of("board-points2.txt", 2),
                Arguments.of("board-points3.txt", 3),
                Arguments.of("board-points4.txt", 4),
                Arguments.of("board-points5.txt", 5),
                Arguments.of("board-points100.txt", 100),
                Arguments.of("board-points200.txt", 200),
                Arguments.of("board-points300.txt", 300),
                Arguments.of("board-points400.txt", 400),
                Arguments.of("board-points500.txt", 500),
                Arguments.of("board-points750.txt", 750),
                Arguments.of("board-points1000.txt", 1000),
                Arguments.of("board-points1250.txt", 1250),
                Arguments.of("board-points1500.txt", 1500),
                Arguments.of("board-points2000.txt", 2000),
                Arguments.of("board-points4410.txt", 4410),
                Arguments.of("board-points4527.txt", 4527),
                Arguments.of("board-points4540.txt", 4540),
                Arguments.of("board-points13464.txt", 13464),
                Arguments.of("board-points26539.txt", 26539)
        );
    }

    /** Every board file in the module, including the degenerate and trap boards. */
    static Stream<String> allBoards() {
        return Stream.concat(
                scoredBoards().map(arguments -> (String) arguments.get()[0]),
                Stream.of(
                        "board4x4.txt", "board0x4.txt", "board4x0.txt",
                        "board-q.txt", "board-16q.txt", "board-aqua.txt", "board-qwerty.txt",
                        "board-noon.txt", "board-dodo.txt", "board-couscous.txt",
                        "board-diagonal.txt", "board-horizontal.txt", "board-vertical.txt",
                        "board-rotavator.txt", "board-estrangers.txt", "board-quinquevalencies.txt",
                        "board-inconsequentially.txt", "board-antidisestablishmentarianisms.txt",
                        "board-dichlorodiphenyltrichloroethanes.txt",
                        "board-pneumonoultramicroscopicsilicovolcanoconiosis.txt"
                )
        );
    }

    /** The headline: the number in the file name is the score, and only yawl produces it. */
    @ParameterizedTest(name = "{0} is worth {1} points")
    @MethodSource("scoredBoards")
    void testTheScoreMatchesTheNumberInTheFileName(String board, int expectedScore) {
        int score = 0;
        for (String word : wordsOn(board)) {
            score += yawlSolver.scoreOf(word);
        }

        Assertions.assertEquals(expectedScore, score, board);
    }

    /**
     * Exact set equality against a solver that shares no data structure with this one. A board
     * cell reused inside a single word, a missed diagonal, or a mishandled {@code Qu} all show up
     * here as a difference.
     */
    @ParameterizedTest(name = "{0} matches an independently written solver")
    @MethodSource("allBoards")
    void testEveryBoardMatchesAnIndependentSolverExactly(String board) {
        Set<String> expected = solveIndependently(new BoggleBoard(board), yawlWords);

        Assertions.assertEquals(expected, wordsOn(board), board);
    }

    @ParameterizedTest(name = "{0} yields only real words of three letters or more")
    @MethodSource("allBoards")
    void testEveryReportedWordIsInTheDictionaryAndLongEnough(String board) {
        for (String word : wordsOn(board)) {
            Assertions.assertTrue(word.length() >= 3, () -> board + ": " + word + " is too short");
            Assertions.assertTrue(
                    yawlSolver.scoreOf(word) > 0,
                    () -> board + ": " + word + " is not a dictionary word"
            );
        }
    }

    /** These boards are named for a word they contain, and they had better contain it. */
    @ParameterizedTest(name = "{0} contains its namesake")
    @ValueSource(strings = {
            "couscous", "dodo", "noon", "rotavator", "estrangers", "quinquevalencies",
            "inconsequentially", "antidisestablishmentarianisms",
            "dichlorodiphenyltrichloroethanes",
            "pneumonoultramicroscopicsilicovolcanoconiosis"
    })
    void testTheWordBoardsContainTheWordTheyAreNamedFor(String word) {
        String board = "board-" + word + ".txt";

        Assertions.assertTrue(
                wordsOn(board).contains(word.toUpperCase()),
                () -> board + " should contain " + word.toUpperCase()
        );
    }

    /**
     * The two traps. Both namesakes are in {@code dictionary-yawl.txt}, so their absence is about
     * the board and the {@code Qu} rule, not the dictionary.
     *
     * <p>On {@code board-qwerty.txt} the {@code Q} in the corner always contributes {@code QU}, so
     * the board spells {@code QUWERTY} and never {@code QWERTY}. On {@code board-aqua.txt} the
     * {@code Qu} cell in the bottom row is adjacent to only one of the two {@code A}s, so
     * {@code A + QU} can only continue into {@code U} - the board yields {@code QUA} and nothing
     * else at all.
     */
    @Test
    void testTheQuRuleMakesTheseNamesakesUnreachable() {
        Assertions.assertTrue(yawlSolver.scoreOf("QWERTY") > 0, "QWERTY is in the dictionary");
        Assertions.assertTrue(yawlSolver.scoreOf("AQUA") > 0, "AQUA is in the dictionary");

        Assertions.assertFalse(
                wordsOn("board-qwerty.txt").contains("QWERTY"),
                "Q always spells QU, so this board gives QUWERTY"
        );
        Assertions.assertEquals(
                Set.of("QUA"), wordsOn("board-aqua.txt"),
                "the Qu cell reaches only one of the two As"
        );
    }

    /**
     * Small boards with a full expected answer, cheap to read and to check by hand. The three
     * direction boards show the search following a diagonal, a row and a column; the two repeated
     * letter boards show a cell being reused across different words but never within one.
     */
    @ParameterizedTest(name = "{0} yields exactly {1}")
    @MethodSource("boardsWithASmallKnownAnswer")
    void testTheSmallBoardsYieldExactlyTheExpectedWords(String board, Set<String> expected) {
        Assertions.assertEquals(expected, wordsOn(board), board);
    }

    static Stream<Arguments> boardsWithASmallKnownAnswer() {
        return Stream.of(
                Arguments.of("board-diagonal.txt", Set.of("HEN", "HEX", "THE", "THEN")),
                Arguments.of("board-horizontal.txt", Set.of("DATA", "TAD", "TAJ", "TYPE")),
                Arguments.of("board-noon.txt", Set.of("NOO", "NOON", "OON")),
                Arguments.of("board-dodo.txt", Set.of("DOD", "DODO", "DOO", "DOODOO", "ODD")),
                Arguments.of("board-vertical.txt", Set.of(
                        "DEX", "DON", "EXERT", "EXODE", "EXON", "NOD", "NODE", "NOX", "ODE",
                        "OXER", "REE", "REX", "TREE"))
        );
    }

    /** A board with no cells has nothing to find, whichever dimension is zero. */
    @ParameterizedTest(name = "{0} has no words")
    @ValueSource(strings = {"board0x4.txt", "board4x0.txt"})
    void testABoardWithNoCellsYieldsNoWords(String board) {
        Assertions.assertTrue(wordsOn(board).isEmpty(), board);
    }

    /**
     * {@code dictionary-16q.txt} holds nothing but runs of {@code QU}, so an all-{@code Qu} board
     * spells one word per path length and each cell contributes two letters - nine cells give an
     * eighteen character word.
     *
     * <p>The board here is built rather than read, because solving the shipped four-by-four
     * {@code board-16q.txt} against this dictionary does not finish quickly. Every prefix of a run
     * of {@code QU} is itself a dictionary prefix, so the search never prunes and walks
     * essentially every simple path in the grid; on top of that {@code TST.keysWithPrefix}
     * collects every matching word into a queue just to answer whether one exists. Three by three
     * takes 80 ms, four by four takes 25 seconds. The structure of the shipped fixture is asserted
     * below instead, and it is still solved against {@code dictionary-yawl.txt} by the
     * independent-solver test, where the first {@code QUQU} prunes immediately.
     */
    @Test
    void testAnAllQuBoardSpellsTwoLettersPerCell() {
        BoggleSolver solver = solverFor("dictionary-16q.txt");
        char[][] letters = new char[3][3];
        for (char[] row : letters) {
            java.util.Arrays.fill(row, 'Q');
        }

        Set<String> words = collect(solver.getAllValidWords(new BoggleBoard(letters)));

        Assertions.assertEquals(8, words.size());
        words.forEach(word -> Assertions.assertEquals(
                "QU".repeat(word.length() / 2), word, "every word is a run of QU"));
        Assertions.assertEquals(
                18, words.stream().mapToInt(String::length).max().orElseThrow(),
                "nine cells, two letters each");
        Assertions.assertEquals(70, words.stream().mapToInt(solver::scoreOf).sum());
    }

    /** The shipped all-Qu fixture: sixteen cells, every one of them a {@code Qu}. */
    @Test
    void testTheShippedAllQuBoardIsSixteenQuCells() {
        BoggleBoard board = new BoggleBoard("board-16q.txt");

        Assertions.assertEquals(4, board.rows());
        Assertions.assertEquals(4, board.cols());
        for (int row = 0; row < board.rows(); row++) {
            for (int column = 0; column < board.cols(); column++) {
                Assertions.assertEquals('Q', board.getLetter(row, column));
            }
        }
    }

    /** Words shorter than three letters are never reported, however many the dictionary holds. */
    @Test
    void testADictionaryOfTwoLetterWordsYieldsNothing() {
        BoggleSolver solver = solverFor("dictionary-2letters.txt");

        Assertions.assertFalse(solver.getAllValidWords(new BoggleBoard("board4x4.txt")).iterator().hasNext());
    }

    /**
     * The two figures written into {@code BoggleSolver.main}'s javadoc, which are the only expected
     * answers the source itself claims.
     */
    @ParameterizedTest(name = "{0} scores {1} against dictionary-algs4.txt")
    @MethodSource("javadocExamples")
    void testTheScoresQuotedInTheSourceJavadoc(String board, int expectedScore) {
        BoggleSolver solver = solverFor("dictionary-algs4.txt");
        Set<String> words = collect(solver.getAllValidWords(new BoggleBoard(board)));

        Assertions.assertEquals(expectedScore, words.stream().mapToInt(solver::scoreOf).sum(), board);
    }

    static Stream<Arguments> javadocExamples() {
        return Stream.of(
                Arguments.of("board4x4.txt", 33),
                Arguments.of("board-q.txt", 84)
        );
    }

    /**
     * Every dictionary shipped with the module, checked against the independent solver on a common
     * board. The large ones are the reason {@code BoggleSolver} uses a {@code TST} at all, and
     * nothing else here loads them.
     */
    @ParameterizedTest(name = "{0} agrees with the independent solver on board4x4")
    @ValueSource(strings = {
            "dictionary-algs4.txt", "dictionary-common.txt", "dictionary-nursery.txt",
            "dictionary-shakespeare.txt", "dictionary-enable2k.txt", "dictionary-twl06.txt",
            "dictionary-sowpods.txt", "dictionary-zingarelli2005.txt"
    })
    void testEveryShippedDictionaryAgreesWithTheIndependentSolver(String dictionary) {
        String[] words = ResourceFiles.open(BoggleSolverFilesTest.class, dictionary).readAllStrings();
        BoggleBoard board = new BoggleBoard("board4x4.txt");

        Set<String> found = collect(new BoggleSolver(words).getAllValidWords(board));

        Assertions.assertEquals(solveIndependently(board, words), found, dictionary);
    }

    private static Set<String> wordsOn(String board) {
        return SOLVED.computeIfAbsent(
                board, name -> collect(yawlSolver.getAllValidWords(new BoggleBoard(name))));
    }

    private static BoggleSolver solverFor(String dictionary) {
        return new BoggleSolver(
                ResourceFiles.open(BoggleSolverFilesTest.class, dictionary).readAllStrings());
    }

    private static Set<String> collect(Iterable<String> words) {
        Set<String> result = new TreeSet<>();
        words.forEach(result::add);
        return result;
    }

    /**
     * A second solver over plain hash sets, used as the oracle.
     *
     * <p>The dictionary is first reduced to the words whose letters all appear on the board, which
     * cannot change the answer - a word needing a letter the board does not have is unreachable -
     * but makes enumerating prefixes affordable.
     */
    private static Set<String> solveIndependently(BoggleBoard board, String[] dictionary) {
        boolean[] onTheBoard = new boolean[26];
        for (int row = 0; row < board.rows(); row++) {
            for (int column = 0; column < board.cols(); column++) {
                char letter = board.getLetter(row, column);
                onTheBoard[letter - 'A'] = true;
                if (letter == 'Q') {
                    onTheBoard['U' - 'A'] = true;
                }
            }
        }

        Set<String> words = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        for (String word : dictionary) {
            if (!isSpellableFrom(word, onTheBoard)) {
                continue;
            }
            words.add(word);
            for (int length = 1; length <= word.length(); length++) {
                prefixes.add(word.substring(0, length));
            }
        }

        Set<String> found = new TreeSet<>();
        boolean[][] used = new boolean[board.rows()][board.cols()];
        for (int row = 0; row < board.rows(); row++) {
            for (int column = 0; column < board.cols(); column++) {
                extend(board, row, column, "", used, prefixes, words, found);
            }
        }
        return found;
    }

    private static boolean isSpellableFrom(String word, boolean[] onTheBoard) {
        for (int index = 0; index < word.length(); index++) {
            if (!onTheBoard[word.charAt(index) - 'A']) {
                return false;
            }
        }
        return true;
    }

    private static void extend(BoggleBoard board, int row, int column, String prefix,
                               boolean[][] used, Set<String> prefixes, Set<String> words,
                               Set<String> found) {
        if (used[row][column]) {
            return;
        }
        char letter = board.getLetter(row, column);
        String extended = prefix + (letter == 'Q' ? "QU" : String.valueOf(letter));
        if (!prefixes.contains(extended)) {
            return;
        }
        if (extended.length() >= 3 && words.contains(extended)) {
            found.add(extended);
        }

        used[row][column] = true;
        for (int rowStep = -1; rowStep <= 1; rowStep++) {
            for (int columnStep = -1; columnStep <= 1; columnStep++) {
                if (rowStep == 0 && columnStep == 0) {
                    continue;
                }
                int nextRow = row + rowStep;
                int nextColumn = column + columnStep;
                if (nextRow >= 0 && nextRow < board.rows()
                        && nextColumn >= 0 && nextColumn < board.cols()) {
                    extend(board, nextRow, nextColumn, extended, used, prefixes, words, found);
                }
            }
        }
        used[row][column] = false;
    }

    /** Guards the memoisation: the boards listed above are the ones the tests actually use. */
    @Test
    void testEveryBoardFileInTheModuleIsCovered() {
        List<String> boards = allBoards().toList();

        Assertions.assertEquals(41, boards.size(), "board fixtures");
        Assertions.assertEquals(boards.size(), new HashSet<>(boards).size(), "no duplicates");
    }
}
