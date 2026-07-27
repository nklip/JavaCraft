import java.util.Comparator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Solver.ManhattanAndHammingComparator} directly.
 *
 * <p>{@code Solver.tryToSolve} only installs this comparator after it has been running for
 * more than 20 seconds, so exercising it through the solver would mean a 20-second test whose
 * outcome depends on machine speed. The comparators and {@link Solver.SearchNode} are
 * package-private, so these tests construct them straight from the same package instead.
 *
 * <p>The comparator ranks a node by {@code manhattan + moves + hamming}. That is worth pinning
 * down, because adding {@code hamming} on top of {@code manhattan + moves} makes the heuristic
 * inadmissible - it can overestimate the real cost of reaching the goal - and A* only returns the
 * shortest solution while its heuristic never overestimates. The last test shows the two
 * comparators strictly disagreeing, which is the mechanism by which swapping them mid-search
 * turns an optimal answer into a merely valid one.
 */
class ManhattanAndHammingComparatorTest {

    private final Comparator<Solver.SearchNode> manhattanAndHamming = new Solver.ManhattanAndHammingComparator();

    /**
     * <pre>
     * 1  2  0      manhattan = 2, hamming = 2
     * 4  5  3
     * 7  8  6
     * </pre>
     */
    private static Board nearGoalBoard() {
        return new Board(new int[][]{
                {1, 2, 0},
                {4, 5, 3},
                {7, 8, 6}
        });
    }

    /**
     * <pre>
     * 1  2  3      manhattan = 3, hamming = 3
     * 0  4  5
     * 7  8  6
     * </pre>
     */
    private static Board midBoard() {
        return new Board(new int[][]{
                {1, 2, 3},
                {0, 4, 5},
                {7, 8, 6}
        });
    }

    /**
     * <pre>
     * 8  1  3      manhattan = 10, hamming = 5
     * 4  0  2
     * 7  6  5
     * </pre>
     */
    private static Board farBoard() {
        return new Board(new int[][]{
                {8, 1, 3},
                {4, 0, 2},
                {7, 6, 5}
        });
    }

    private static Solver.SearchNode searchNode(Board board, int moves) {
        return new Solver.SearchNode(null, board, (short) moves);
    }

    @Test
    void testBoardMetricsUsedByTheseTests() {
        // the ordering assertions below are written against these values
        Assertions.assertEquals(2, nearGoalBoard().manhattan());
        Assertions.assertEquals(2, nearGoalBoard().hamming());
        Assertions.assertEquals(3, midBoard().manhattan());
        Assertions.assertEquals(3, midBoard().hamming());
        Assertions.assertEquals(10, farBoard().manhattan());
        Assertions.assertEquals(5, farBoard().hamming());
    }

    @Test
    void testRanksTheBoardCloserToTheGoalFirst() {
        Solver.SearchNode nearer = searchNode(nearGoalBoard(), 0);   // 2 + 0 + 2 = 4
        Solver.SearchNode further = searchNode(midBoard(), 0);       // 3 + 0 + 3 = 6

        Assertions.assertTrue(manhattanAndHamming.compare(nearer, further) < 0);
        Assertions.assertTrue(manhattanAndHamming.compare(further, nearer) > 0);
    }

    @Test
    void testCountsMovesAlreadyMadeTowardsThePriority() {
        Solver.SearchNode cheapBoardDeepInTheSearch = searchNode(nearGoalBoard(), 5); // 2 + 5 + 2 = 9
        Solver.SearchNode dearerBoardAtTheStart = searchNode(midBoard(), 0);          // 3 + 0 + 3 = 6

        Assertions.assertTrue(
                manhattanAndHamming.compare(cheapBoardDeepInTheSearch, dearerBoardAtTheStart) > 0
        );
    }

    @Test
    void testTreatsEqualCombinedPrioritiesAsTies() {
        Solver.SearchNode viaNearBoard = searchNode(nearGoalBoard(), 2); // 2 + 2 + 2 = 6
        Solver.SearchNode viaMidBoard = searchNode(midBoard(), 0);       // 3 + 0 + 3 = 6

        Assertions.assertEquals(0, manhattanAndHamming.compare(viaNearBoard, viaMidBoard));
        Assertions.assertEquals(0, manhattanAndHamming.compare(viaMidBoard, viaNearBoard));
    }

    /**
     * The inadmissibility made concrete. {@link Solver.ManhattanComparator} scores by
     * {@code manhattan + moves} and puts {@code far} first; adding {@code hamming} reverses that.
     * Swapping comparators part-way through a search therefore reorders the queue, which is how
     * the 20-second fallback in {@code Solver.tryToSolve} ends up reporting a longer solution
     * than the shortest one.
     */
    @Test
    void testDisagreesWithTheManhattanComparatorItReplaces() {
        Comparator<Solver.SearchNode> manhattanOnly = new Solver.ManhattanComparator();

        Solver.SearchNode far = searchNode(farBoard(), 0);                   // 10 + 0 = 10, +5 = 15
        Solver.SearchNode nearButDeep = searchNode(nearGoalBoard(), 10);     //  2 + 10 = 12, +2 = 14

        Assertions.assertTrue(
                manhattanOnly.compare(far, nearButDeep) < 0,
                "manhattan + moves ranks the far board first"
        );
        Assertions.assertTrue(
                manhattanAndHamming.compare(far, nearButDeep) > 0,
                "adding hamming flips the order, so the two comparators disagree"
        );
    }

    /**
     * The third comparator, used for the twin board that decides solvability. It ignores moves
     * entirely and orders on {@code hamming} alone.
     */
    @Test
    void testHammingComparatorOrdersOnHammingAloneAndIgnoresMoves() {
        Comparator<Solver.SearchNode> hammingOnly = new Solver.HammingComparator();

        Solver.SearchNode fewerWrongTiles = searchNode(nearGoalBoard(), 99); // hamming 2
        Solver.SearchNode moreWrongTiles = searchNode(midBoard(), 0);        // hamming 3

        Assertions.assertTrue(hammingOnly.compare(fewerWrongTiles, moreWrongTiles) < 0);
        Assertions.assertEquals(
                0,
                hammingOnly.compare(searchNode(midBoard(), 0), searchNode(midBoard(), 50)),
                "moves must not influence the hamming comparator"
        );
    }
}
