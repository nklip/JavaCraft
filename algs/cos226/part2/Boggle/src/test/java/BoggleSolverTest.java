import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class BoggleSolverTest {

    @Test
    void testFindsDictionaryWordsWithoutReusingBoardCells() {
        BoggleSolver solver = new BoggleSolver(new String[]{
                "ART", "QUA", "QUART", "RAT", "TAR", "AT", "QUARTS"
        });
        BoggleBoard board = new BoggleBoard(new char[][]{
                {'Q', 'A'},
                {'R', 'T'}
        });

        Set<String> words = new HashSet<>();
        solver.getAllValidWords(board).forEach(words::add);

        Assertions.assertEquals(Set.of("ART", "QUA", "QUART", "RAT", "TAR"), words);
    }

    @Test
    void testScoresOnlyDictionaryWords() {
        BoggleSolver solver = new BoggleSolver(new String[]{
                "AA", "AAA", "AAAA", "AAAAA", "AAAAAA", "AAAAAAA", "AAAAAAAA"
        });

        Assertions.assertEquals(0, solver.scoreOf("AA"));
        Assertions.assertEquals(1, solver.scoreOf("AAA"));
        Assertions.assertEquals(1, solver.scoreOf("AAAA"));
        Assertions.assertEquals(2, solver.scoreOf("AAAAA"));
        Assertions.assertEquals(3, solver.scoreOf("AAAAAA"));
        Assertions.assertEquals(5, solver.scoreOf("AAAAAAA"));
        Assertions.assertEquals(11, solver.scoreOf("AAAAAAAA"));
        Assertions.assertEquals(0, solver.scoreOf("UNKNOWN"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> solver.scoreOf(null));
    }

    /**
     * Every dictionary shipped with the module is in alphabetical order, so the {@code TST} behind
     * the solver is only ever grown to the right and takes a very different shape from one built
     * out of unsorted input. The answers must not depend on that shape.
     */
    @Test
    void testTheOrderOfTheDictionaryDoesNotChangeTheAnswers() {
        String[] sorted = {"ART", "AT", "QUA", "QUART", "QUARTS", "RAT", "TAR"};
        String[] reversed = {"TAR", "RAT", "QUARTS", "QUART", "QUA", "AT", "ART"};
        BoggleBoard board = new BoggleBoard(new char[][]{
                {'Q', 'A'},
                {'R', 'T'}
        });

        Set<String> fromSorted = new HashSet<>();
        new BoggleSolver(sorted).getAllValidWords(board).forEach(fromSorted::add);
        Set<String> fromReversed = new HashSet<>();
        new BoggleSolver(reversed).getAllValidWords(board).forEach(fromReversed::add);

        Assertions.assertEquals(Set.of("ART", "QUA", "QUART", "RAT", "TAR"), fromSorted);
        Assertions.assertEquals(fromSorted, fromReversed);
        Assertions.assertEquals(1, new BoggleSolver(reversed).scoreOf("RAT"));
        Assertions.assertEquals(0, new BoggleSolver(reversed).scoreOf("ARQ"));
    }

    /**
     * A {@code Q} cell always spells {@code QU}, so a dictionary whose {@code Q} words all carry on
     * with something else is a dead end the moment that cell is entered: no word has {@code QU} as
     * a prefix, even though several start with {@code Q}.
     */
    @Test
    void testAQCellIsADeadEndWhenNoWordContinuesWithU() {
        BoggleSolver solver = new BoggleSolver(new String[]{"QAT", "QI"});
        BoggleBoard board = new BoggleBoard(new char[][]{
                {'Q', 'A'},
                {'T', 'I'}
        });

        Assertions.assertFalse(solver.getAllValidWords(board).iterator().hasNext());
    }

    /**
     * Only that {@code main} runs. It prints through {@code StdOut}, which copies
     * {@code System.out} once in its static initializer, so capturing the output would depend on
     * class load order. The scores it prints are asserted in {@code BoggleSolverFilesTest}, which
     * calls the solver directly.
     *
     * <p>Two arguments are needed before either is read, so the single-argument call below still
     * uses the built-in defaults.
     */
    @Test
    void testMainRunsToCompletion() {
        Assertions.assertDoesNotThrow(
                () -> BoggleSolver.main(new String[]{"dictionary-algs4.txt", "board4x4.txt"}));
        Assertions.assertDoesNotThrow(() -> BoggleSolver.main(new String[0]));
        Assertions.assertDoesNotThrow(() -> BoggleSolver.main(new String[]{"ignored"}));
        Assertions.assertDoesNotThrow(() -> BoggleSolver.main(null));
    }
}
