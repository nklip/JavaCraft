import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

class SolverTest {

    @Test
    void testRejectsNullInitialBoard() {
        Assertions.assertThrows(NullPointerException.class, () -> new Solver(null));
    }

    @Test
    void testRecognizesAlreadySolvedBoard() {
        Board initial = board(
                1, 2, 3,
                4, 5, 6,
                7, 8, 0
        );

        Solver solver = new Solver(initial);

        Assertions.assertTrue(solver.isSolvable());
        Assertions.assertEquals(0, solver.moves());
        Assertions.assertEquals(List.of(initial), toList(solver.solution()));
    }

    @Test
    void testReturnsTheShortestSolution() {
        Board initial = board(
                0, 1, 3,
                4, 2, 5,
                7, 8, 6
        );

        Solver solver = Assertions.assertTimeout(Duration.ofSeconds(2), () -> new Solver(initial));
        List<Board> solution = toList(solver.solution());

        Assertions.assertTrue(solver.isSolvable());
        Assertions.assertEquals(4, solver.moves());
        Assertions.assertEquals(initial, solution.getFirst());
        Assertions.assertTrue(solution.getLast().isGoal());
        Assertions.assertEquals(solver.moves() + 1, solution.size());
    }

    @Test
    void testIdentifiesUnsolvableBoard() {
        Board initial = new Board(new int[][]{
                {1, 0},
                {2, 3}
        });

        Solver solver = Assertions.assertTimeout(Duration.ofSeconds(2), () -> new Solver(initial));

        Assertions.assertFalse(solver.isSolvable());
        Assertions.assertEquals(-1, solver.moves());
        Assertions.assertNull(solver.solution());
    }

    private static Board board(int... tiles) {
        int size = (int) Math.sqrt(tiles.length);
        int[][] blocks = new int[size][size];
        for (int index = 0; index < tiles.length; index++) {
            blocks[index / size][index % size] = tiles[index];
        }
        return new Board(blocks);
    }

    private static List<Board> toList(Iterable<Board> boards) {
        List<Board> result = new ArrayList<>();
        boards.forEach(result::add);
        return result;
    }
}
