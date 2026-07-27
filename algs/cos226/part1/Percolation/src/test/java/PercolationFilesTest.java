import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives {@link Percolation} from the grid files in {@code src/test/resources}.
 *
 * <p>Each file starts with the grid size and then lists the sites to open as {@code row column}
 * pairs. The names carry the expected answer: a {@code -no} suffix marks a grid that does not
 * percolate, and the plain {@code inputN.txt} files do. The two artistic grids that do not
 * percolate - {@code heart25.txt} and {@code greeting57.txt} - are the exception to the naming, so
 * they are listed explicitly.
 *
 * <p>The oracle for {@code isFull} is a breadth-first flood fill from the open sites in the top
 * row, written out in {@link PercolationFilesTest.Grid#floodFillFromTheTopRow}. It is deliberately not a union-find: the
 * point is to check the two weighted quick-union structures in {@code Percolation} against
 * something whose correctness is obvious by inspection, in the same way {@code PointSET} checks
 * {@code KdTree}.
 *
 * <p>That oracle is what makes the backwash test possible, and backwash is the classic defect in
 * this exercise. An implementation that wires a virtual bottom site into the same union-find it
 * uses for {@code isFull} will report sites in a disconnected bottom region as full once the grid
 * percolates, because they reach the top through the virtual bottom rather than through open
 * sites. {@code Percolation} keeps a second structure for exactly this reason, and the artistic
 * grids - which are full of enclosed regions - are where a regression would show first.
 */
class PercolationFilesTest {

    /** Grids that percolate, with their size. */
    static Stream<Arguments> percolatingFixtures() {
        return Stream.of(
                Arguments.of("input1.txt", 1),
                Arguments.of("input2.txt", 2),
                Arguments.of("input3.txt", 3),
                Arguments.of("input4.txt", 4),
                Arguments.of("input5.txt", 5),
                Arguments.of("input6.txt", 6),
                Arguments.of("input7.txt", 7),
                Arguments.of("input8.txt", 8),
                Arguments.of("input10.txt", 10),
                Arguments.of("input20.txt", 20),
                Arguments.of("input50.txt", 50),
                Arguments.of("jerry47.txt", 47),
                Arguments.of("sedgewick60.txt", 60),
                Arguments.of("michael61.txt", 61),
                Arguments.of("wayne98.txt", 98)
        );
    }

    /** Grids that do not percolate: the {@code -no} files, plus the two artistic exceptions. */
    static Stream<Arguments> nonPercolatingFixtures() {
        return Stream.of(
                Arguments.of("input1-no.txt", 1),
                Arguments.of("input2-no.txt", 2),
                Arguments.of("input8-no.txt", 8),
                Arguments.of("input10-no.txt", 10),
                Arguments.of("heart25.txt", 25),
                Arguments.of("greeting57.txt", 57)
        );
    }

    static Stream<Arguments> allFixtures() {
        return Stream.concat(percolatingFixtures(), nonPercolatingFixtures());
    }

    @ParameterizedTest(name = "{0} percolates")
    @MethodSource("percolatingFixtures")
    void testTheFixturesWithoutTheNoSuffixPercolate(String fixture, int expectedSize) {
        Grid grid = Grid.read(fixture);

        Assertions.assertEquals(expectedSize, grid.size, fixture + ": fixture changed");
        Assertions.assertTrue(grid.percolation.percolates(), fixture);
    }

    @ParameterizedTest(name = "{0} does not percolate")
    @MethodSource("nonPercolatingFixtures")
    void testTheNoSuffixFixturesDoNotPercolate(String fixture, int expectedSize) {
        Grid grid = Grid.read(fixture);

        Assertions.assertEquals(expectedSize, grid.size, fixture + ": fixture changed");
        Assertions.assertFalse(grid.percolation.percolates(), fixture);
    }

    /** {@code percolates} must agree with the flood fill reaching the bottom row. */
    @ParameterizedTest(name = "{0}: percolates agrees with the flood fill")
    @MethodSource("allFixtures")
    void testPercolatesAgreesWithTheFloodFill(String fixture, int ignoredSize) {
        Grid grid = Grid.read(fixture);
        boolean[][] full = grid.floodFillFromTheTopRow();

        boolean reachesTheBottom = false;
        for (int column = 1; column <= grid.size; column++) {
            reachesTheBottom |= full[grid.size][column];
        }

        Assertions.assertEquals(reachesTheBottom, grid.percolation.percolates(), fixture);
    }

    /**
     * The backwash test. Every site is checked, so a site reported full that the flood fill cannot
     * reach fails here - which is what backwash looks like.
     */
    @ParameterizedTest(name = "{0}: isFull agrees with the flood fill on every site")
    @MethodSource("allFixtures")
    void testIsFullAgreesWithTheFloodFillOnEverySite(String fixture, int ignoredSize) {
        Grid grid = Grid.read(fixture);
        boolean[][] full = grid.floodFillFromTheTopRow();

        for (int row = 1; row <= grid.size; row++) {
            for (int column = 1; column <= grid.size; column++) {
                int i = row;
                int j = column;
                Assertions.assertEquals(
                        full[i][j],
                        grid.percolation.isFull(i, j),
                        () -> fixture + ": isFull(" + i + ", " + j + ")"
                                + (full[i][j] ? " should be full" : " is backwash")
                );
            }
        }
    }

    @ParameterizedTest(name = "{0}: isOpen reports exactly the sites the file opens")
    @MethodSource("allFixtures")
    void testIsOpenReportsExactlyTheSitesTheFileOpens(String fixture, int ignoredSize) {
        Grid grid = Grid.read(fixture);

        for (int row = 1; row <= grid.size; row++) {
            for (int column = 1; column <= grid.size; column++) {
                int i = row;
                int j = column;
                Assertions.assertEquals(
                        grid.open[i][j],
                        grid.percolation.isOpen(i, j),
                        () -> fixture + ": isOpen(" + i + ", " + j + ")"
                );
            }
        }
    }

    /** A full site is by definition an open site, on every grid. */
    @ParameterizedTest(name = "{0}: every full site is open")
    @MethodSource("allFixtures")
    void testOnlyOpenSitesAreEverFull(String fixture, int ignoredSize) {
        Grid grid = Grid.read(fixture);

        for (int row = 1; row <= grid.size; row++) {
            for (int column = 1; column <= grid.size; column++) {
                int i = row;
                int j = column;
                if (grid.percolation.isFull(i, j)) {
                    Assertions.assertTrue(
                            grid.percolation.isOpen(i, j),
                            () -> fixture + ": (" + i + ", " + j + ") is full but not open"
                    );
                }
            }
        }
    }

    /**
     * These three files list some sites more than once - {@code input50.txt} has 2099 pairs for
     * 1412 distinct sites - so replaying them exercises {@code open}'s early return. Opening every
     * site a second time must change nothing at all.
     */
    @ParameterizedTest(name = "{0}: reopening every site changes nothing")
    @ValueSource(strings = {"input5.txt", "input8.txt", "input50.txt", "wayne98.txt", "heart25.txt"})
    void testReopeningSitesIsANoOp(String fixture) {
        Grid grid = Grid.read(fixture);
        boolean percolatedBefore = grid.percolation.percolates();
        boolean[][] fullBefore = new boolean[grid.size + 2][grid.size + 2];
        for (int i = 1; i <= grid.size; i++) {
            for (int j = 1; j <= grid.size; j++) {
                fullBefore[i][j] = grid.percolation.isFull(i, j);
            }
        }

        for (int i = 1; i <= grid.size; i++) {
            for (int j = 1; j <= grid.size; j++) {
                if (grid.open[i][j]) {
                    grid.percolation.open(i, j);
                }
            }
        }

        Assertions.assertEquals(percolatedBefore, grid.percolation.percolates(), fixture);
        for (int row = 1; row <= grid.size; row++) {
            for (int column = 1; column <= grid.size; column++) {
                int i = row;
                int j = column;
                Assertions.assertEquals(
                        fullBefore[i][j],
                        grid.percolation.isFull(i, j),
                        () -> fixture + ": isFull(" + i + ", " + j + ") changed on reopening"
                );
            }
        }
    }

    /**
     * {@code percolates} caches its answer once it is true, so it has to stay true when asked
     * again. Nothing else reaches that branch: every other test asks at most once.
     */
    @ParameterizedTest(name = "{0}: percolates stays true when asked repeatedly")
    @MethodSource("percolatingFixtures")
    void testPercolatesIsStableAcrossRepeatedCalls(String fixture, int ignoredSize) {
        Grid grid = Grid.read(fixture);

        Assertions.assertTrue(grid.percolation.percolates(), fixture + ": first call");
        Assertions.assertTrue(grid.percolation.percolates(), fixture + ": cached call");
        Assertions.assertTrue(grid.percolation.percolates(), fixture + ": and again");
    }

    /** Bounds are validated against the grid size read from the file, not a hard-coded one. */
    @ParameterizedTest(name = "{0}: rejects coordinates outside the grid")
    @MethodSource("allFixtures")
    void testRejectsCoordinatesOutsideTheGridReadFromTheFile(String fixture, int ignoredSize) {
        Percolation percolation = Grid.read(fixture).percolation;
        int outside = ignoredSize + 1;

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.isOpen(0, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.isOpen(outside, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.isOpen(1, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.isOpen(1, outside));
    }

    /**
     * The naming convention the fixture lists above rely on, asserted rather than assumed: a
     * {@code -no} file and its plain counterpart differ, and the {@code -no} one is the smaller.
     */
    @Test
    void testTheNoSuffixFilesAreTheNonPercolatingVariantOfTheirCounterpart() {
        for (String stem : new String[]{"input1", "input2", "input8", "input10"}) {
            Grid percolating = Grid.read(stem + ".txt");
            Grid blocked = Grid.read(stem + "-no.txt");

            Assertions.assertEquals(percolating.size, blocked.size, stem + ": same grid size");
            Assertions.assertTrue(percolating.percolation.percolates(), stem + ".txt");
            Assertions.assertFalse(blocked.percolation.percolates(), stem + "-no.txt");
            Assertions.assertTrue(
                    blocked.openCount < percolating.openCount,
                    stem + "-no.txt should open fewer sites than " + stem + ".txt"
            );
        }
    }

    /** A grid file, replayed into a {@link Percolation} and kept alongside the raw site list. */
    private static final class Grid {

        private final int size;
        private final boolean[][] open;
        private final int openCount;
        private final Percolation percolation;

        private Grid(int size, boolean[][] open, int openCount, Percolation percolation) {
            this.size = size;
            this.open = open;
            this.openCount = openCount;
            this.percolation = percolation;
        }

        static Grid read(String fixture) {
            In in = ResourceFiles.open(PercolationFilesTest.class, fixture);
            int size = in.readInt();
            boolean[][] open = new boolean[size + 2][size + 2];
            Percolation percolation = new Percolation(size);
            int openCount = 0;

            while (!in.isEmpty()) {
                int row = in.readInt();
                int column = in.readInt();
                if (!open[row][column]) {
                    openCount++;
                }
                open[row][column] = true;
                percolation.open(row, column);
            }
            in.close();

            return new Grid(size, open, openCount, percolation);
        }

        /**
         * Breadth-first search from every open site in the top row through orthogonally adjacent
         * open sites. A site is full exactly when this reaches it - no union-find involved.
         */
        boolean[][] floodFillFromTheTopRow() {
            boolean[][] full = new boolean[size + 2][size + 2];
            Deque<int[]> queue = new ArrayDeque<>();

            for (int column = 1; column <= size; column++) {
                if (open[1][column]) {
                    full[1][column] = true;
                    queue.add(new int[]{1, column});
                }
            }

            int[] rowSteps = {-1, 1, 0, 0};
            int[] columnSteps = {0, 0, -1, 1};
            while (!queue.isEmpty()) {
                int[] site = queue.poll();
                for (int step = 0; step < rowSteps.length; step++) {
                    int row = site[0] + rowSteps[step];
                    int column = site[1] + columnSteps[step];
                    if (row >= 1 && row <= size && column >= 1 && column <= size
                            && open[row][column] && !full[row][column]) {
                        full[row][column] = true;
                        queue.add(new int[]{row, column});
                    }
                }
            }
            return full;
        }
    }
}
