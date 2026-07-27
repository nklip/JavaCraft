import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives {@link Solver} from the {@code puzzle*.txt} fixtures in {@code src/test/resources}.
 * <p>
 * The fixture names are the expected results: {@code puzzleNN.txt} is solvable in exactly NN
 * moves, and the {@code *-unsolvable*.txt} boards have no solution at all. That makes the whole
 * directory usable as a table of expected values instead of hand-written boards.
 * <p>
 * Every solvable fixture used here solves in under 400 ms, which matters for more than speed:
 * once a solve passes 20 seconds {@code Solver.tryToSolve} switches to an inadmissible comparator
 * and starts returning non-optimal move counts, so slow fixtures would assert against a
 * time-dependent answer.
 * <p>
 * The excluded ones, with measured times:
 * puzzle32-46 and puzzle48 take 0.7-7 s;
 * puzzle50 takes 27 s and crosses that threshold;
 * puzzle47 and puzzle49 exhaust a 2 GB heap with {@code OutOfMemoryError}; and the
 * {@code puzzle4x4-78/80/hard1/hard2} boards run past 45 s.
 */
class SolverPuzzleFilesTest {

    /** puzzle00..puzzle30 each solve in well under 100 ms. */
    private static final int FAST_FIXTURE_LIMIT = 31;

    /** Deeper searches that still finish in well under half a second. */
    private static final int[] DEEPER_FIXTURES = {34, 37, 39, 41, 44};

    static Stream<Arguments> solvablePuzzles() {
        return Stream.concat(
                        IntStream.rangeClosed(0, FAST_FIXTURE_LIMIT).boxed(),
                        IntStream.of(DEEPER_FIXTURES).boxed())
                .map(moves -> Arguments.of("puzzle%02d.txt".formatted(moves), moves));
    }

    @ParameterizedTest(name = "{0} is solvable in {1} moves")
    @MethodSource("solvablePuzzles")
    void testSolvesEachPuzzleFileInTheExpectedNumberOfMoves(String fixture, int expectedMoves) {
        Board initial = readBoard(fixture);

        Solver solver = new Solver(initial);

        Assertions.assertTrue(solver.isSolvable(), fixture + " should be solvable");
        Assertions.assertEquals(expectedMoves, solver.moves(), fixture + " move count");
        assertSolutionIsAValidMoveSequence(initial, solver);
    }

    @ParameterizedTest(name = "{0} has no solution")
    @ValueSource(strings = {
            "puzzle2x2-unsolvable1.txt",
            "puzzle2x2-unsolvable2.txt",
            "puzzle2x2-unsolvable3.txt",
            "puzzle3x3-unsolvable.txt",
            "puzzle3x3-unsolvable1.txt",
            "puzzle3x3-unsolvable2.txt",
            "puzzle4x4-unsolvable.txt"
    })
    void testDetectsUnsolvablePuzzleFiles(String fixture) {
        Solver solver = new Solver(readBoard(fixture));

        Assertions.assertFalse(solver.isSolvable(), fixture + " should be unsolvable");
        Assertions.assertEquals(-1, solver.moves());
        Assertions.assertNull(solver.solution());
    }

    /**
     * Covers {@link Solver#main} end to end: reading a board from a fixture, solving it and
     * printing the result, for both the solvable and the unsolvable branch.
     *
     * <p>The solution text itself is not asserted. {@code main} prints through {@code StdOut},
     * which captures {@code System.out} into a private static field when the class is first
     * loaded and offers no way to redirect it afterwards, so its output cannot be captured
     * reliably from inside a shared test JVM. The {@code Difference = } line is written straight
     * to {@code System.out} by {@code main}, so it is a dependable signal that the whole method
     * ran. The printed values themselves are asserted against {@link Solver} directly above.
     */
    @ParameterizedTest(name = "main runs to completion for {0}")
    @ValueSource(strings = {"puzzle04.txt", "puzzle3x3-unsolvable.txt"})
    void testMainReadsAFixtureAndReportsAResult(String fixture) {
        String output = Assertions.assertDoesNotThrow(() -> runMain(fixture));

        Assertions.assertTrue(
                output.contains("Difference = "),
                () -> "expected main to run to completion but stdout was:\n" + output
        );
    }

    /**
     * Every step must be a legal single-tile move, the walk must start at the initial board and
     * end at the goal, and its length must agree with {@link Solver#moves()}.
     */
    private static void assertSolutionIsAValidMoveSequence(Board initial, Solver solver) {
        List<Board> solution = new ArrayList<>();
        solver.solution().forEach(solution::add);

        Assertions.assertEquals(solver.moves() + 1, solution.size(), "solution length");
        Assertions.assertEquals(initial, solution.getFirst(), "solution must start at the initial board");
        Assertions.assertTrue(solution.getLast().isGoal(), "solution must end at the goal board");

        for (int step = 1; step < solution.size(); step++) {
            Board previous = solution.get(step - 1);
            Board current = solution.get(step);
            List<Board> neighbours = new ArrayList<>();
            previous.neighbors().forEach(neighbours::add);
            int index = step;
            Assertions.assertTrue(
                    neighbours.contains(current),
                    () -> "board at index " + index + " is not reachable in one move from its predecessor"
            );
        }
    }

    private static String runMain(String fixture) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Solver.main(new String[]{fixture});
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static Board readBoard(String fixture) {
        try (InputStream stream = SolverPuzzleFilesTest.class.getResourceAsStream("/" + fixture)) {
            Assertions.assertNotNull(stream, "missing test resource: " + fixture);
            Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8);
            int size = scanner.nextInt();
            int[][] blocks = new int[size][size];
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    blocks[row][column] = scanner.nextInt();
                }
            }
            return new Board(blocks);
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + fixture, e);
        }
    }
}
