import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives {@link KdTree} from the point files in {@code src/test/resources}, cross-checking it
 * against {@link PointSET}.
 *
 * <p>{@code PointSET} is the brute-force implementation of the same API - a {@code TreeSet} with
 * a linear scan for {@code range} and {@code nearest} - so it is a ready-made oracle. Anything the
 * kd-tree gets wrong in its pruning shows up as a disagreement, and the fixtures supply the point
 * distributions that pruning is sensitive to.
 *
 * <p>Two fixtures matter more than their size suggests. Every point in {@code vertical7.txt}
 * shares an x of 0.3 and every point in {@code horizontal8.txt} shares a y of 0.5, so they force
 * {@code KdTree.compare} to fall through to its second coordinate - the tie-break that decides
 * which subtree a point on the splitting line belongs to. Nothing else in the module reaches it.
 *
 * <p>{@code nearest} is asserted on distance rather than on point identity. The {@code circleN}
 * fixtures place their points on a circle, so a query near the centre is equidistant from many of
 * them; the two implementations then legitimately return different points. The contract is "a
 * nearest neighbour", not "this particular one".
 *
 * <p>The fixtures stop at {@code input80K.txt}. What limits the size now is the brute-force
 * oracle, not the kd-tree: {@code PointSET.nearest} scans every point, so cross-checking 100
 * queries costs 100 full passes over the file. The larger fixtures shipped with the module -
 * {@code input100K.txt} through {@code input1M.txt} - would buy no extra coverage for that price,
 * because the code paths they exercise are the ones the smaller files already reach.
 *
 * <p>{@code KdTree.draw}, {@code PointSET.draw} and {@code RectHV.draw} are deliberately not
 * covered. They call {@code StdDraw}, whose static initializer throws
 * {@code ExceptionInInitializerError} when {@code java.awt.headless} is true, so a test touching
 * them would pass locally and fail on any headless machine.
 */
class KdTreePointFilesTest {

    /** Fixed seed: the query points must be the same on every run. */
    private static final long SEED = 20260727L;

    /** Fixtures small enough to cross-check exhaustively against the brute-force implementation. */
    static Stream<Arguments> smallFixtures() {
        return Stream.of(
                Arguments.of("circle4.txt", 4),
                Arguments.of("vertical7.txt", 7),
                Arguments.of("horizontal8.txt", 8),
                Arguments.of("circle10.txt", 10),
                Arguments.of("circle10k.txt", 11),
                Arguments.of("circle100.txt", 100),
                Arguments.of("circle1000.txt", 1000)
        );
    }

    /** Big enough that a wrong pruning rule has room to show, small enough to stay quick. */
    static Stream<Arguments> largeFixtures() {
        return Stream.of(
                Arguments.of("circle10000.txt", 10000),
                Arguments.of("input10K.txt", 10000),
                Arguments.of("input20K.txt", 20000)
        );
    }

    static Stream<Arguments> allFixtures() {
        return Stream.concat(smallFixtures(), largeFixtures());
    }

    @ParameterizedTest(name = "{0} holds {1} distinct points")
    @MethodSource("allFixtures")
    void testSizeMatchesTheDistinctPointCount(String fixture, int expectedSize) {
        List<Point2D> points = readPoints(fixture);
        Set<Point2D> distinct = new HashSet<>(points);

        KdTree tree = build(points);

        Assertions.assertEquals(distinct.size(), expectedSize, fixture + ": fixture changed");
        Assertions.assertEquals(expectedSize, tree.size(), fixture);
        Assertions.assertEquals(expectedSize, brute(points).size(), fixture);
        Assertions.assertFalse(tree.isEmpty(), fixture);
    }

    /** Re-inserting the whole file must not grow the tree. */
    @ParameterizedTest(name = "{0} inserted twice still holds {1} points")
    @MethodSource("smallFixtures")
    void testReinsertingEveryPointChangesNothing(String fixture, int expectedSize) {
        List<Point2D> points = readPoints(fixture);

        KdTree tree = build(points);
        points.forEach(tree::insert);

        Assertions.assertEquals(expectedSize, tree.size(), fixture);
    }

    @ParameterizedTest(name = "{0}: every point is found again")
    @MethodSource("allFixtures")
    void testContainsFindsEveryPointThatWasInserted(String fixture, int ignoredSize) {
        List<Point2D> points = readPoints(fixture);

        KdTree tree = build(points);

        points.forEach(point -> Assertions.assertTrue(tree.contains(point), () -> fixture + ": " + point));
    }

    /**
     * The counterpart to the test above: points that are not in the file must not be reported as
     * present. The probes are shifted off every fixture point by a wide margin, and the
     * brute-force set confirms they really are absent rather than a badly chosen probe.
     */
    @ParameterizedTest(name = "{0}: absent points are not found")
    @MethodSource("smallFixtures")
    void testContainsRejectsPointsThatAreNotInTheFile(String fixture, int ignoredSize) {
        List<Point2D> points = readPoints(fixture);
        KdTree tree = build(points);
        PointSET reference = brute(points);

        for (Point2D probe : queryPoints(37)) {
            if (!reference.contains(probe)) {
                Assertions.assertFalse(tree.contains(probe), () -> fixture + ": " + probe);
            }
        }
    }

    @ParameterizedTest(name = "{0}: range agrees with brute force")
    @MethodSource("allFixtures")
    void testRangeAgreesWithBruteForce(String fixture, int ignoredSize) {
        List<Point2D> points = readPoints(fixture);
        KdTree tree = build(points);
        PointSET reference = brute(points);

        for (RectHV rect : queryRectangles()) {
            Set<Point2D> fromTree = toSet(tree.range(rect));
            Set<Point2D> fromBruteForce = toSet(reference.range(rect));

            Assertions.assertEquals(fromBruteForce, fromTree, () -> fixture + " over " + rect);
        }
    }

    @ParameterizedTest(name = "{0}: the whole unit square returns every point")
    @MethodSource("allFixtures")
    void testRangeOverTheUnitSquareReturnsEveryPoint(String fixture, int expectedSize) {
        List<Point2D> points = readPoints(fixture);

        List<Point2D> reported = toList(build(points).range(new RectHV(0.0, 0.0, 1.0, 1.0)));

        Assertions.assertEquals(expectedSize, reported.size(), fixture);
        Assertions.assertEquals(
                expectedSize,
                new HashSet<>(reported).size(),
                fixture + ": no point reported twice"
        );
    }

    /**
     * {@code range} used to scan its accumulating result list for duplicates, which made it
     * quadratic in the number of points reported: these queries took 5.5 s on this fixture and
     * 1.4 s on {@code input40K.txt}, against 4 ms now. The guard was unnecessary - the traversal
     * reaches every node at most once and {@code insert} keeps the tree free of equal points.
     * <p>
     * The assertions are the two halves of that argument: the results still match brute force, and
     * nothing is reported twice. The fixture size is the regression protection - reintroducing a
     * linear scan would stall this test rather than fail it quietly.
     */
    @Test
    void testRangeStaysCheapOnALargeFixture() {
        List<Point2D> points = readPoints("input80K.txt");
        KdTree tree = build(points);
        PointSET reference = brute(points);

        for (RectHV rect : queryRectangles()) {
            List<Point2D> reported = toList(tree.range(rect));

            Assertions.assertEquals(
                    new HashSet<>(reported).size(),
                    reported.size(),
                    () -> "input80K.txt over " + rect + ": no point reported twice"
            );
            Assertions.assertEquals(
                    toSet(reference.range(rect)),
                    new HashSet<>(reported),
                    () -> "input80K.txt over " + rect
            );
        }
    }

    @ParameterizedTest(name = "{0}: nearest agrees with brute force")
    @MethodSource("allFixtures")
    void testNearestAgreesWithBruteForce(String fixture, int ignoredSize) {
        List<Point2D> points = readPoints(fixture);
        KdTree tree = build(points);
        PointSET reference = brute(points);

        for (Point2D query : queryPoints(100)) {
            Point2D fromTree = tree.nearest(query);
            Point2D fromBruteForce = reference.nearest(query);

            Assertions.assertNotNull(fromTree, () -> fixture + ": no neighbour for " + query);
            Assertions.assertEquals(
                    query.distanceTo(fromBruteForce),
                    query.distanceTo(fromTree),
                    () -> fixture + ": " + fromTree + " is further from " + query
                            + " than " + fromBruteForce
            );
        }
    }

    /**
     * On these fixtures no query has two points at the same distance, so the nearest neighbour is
     * unique and the two implementations must return the very same point - a stricter contract
     * than the distance equality asserted above, and the one that pins down the pruning bound in
     * {@code findNearest}. Uniqueness is asserted rather than assumed, so the test fails loudly
     * if a fixture ever changes and quietly acquires a tie.
     */
    @ParameterizedTest(name = "{0}: the unique nearest neighbour is the same point")
    @ValueSource(strings = {"vertical7.txt", "horizontal8.txt", "input10K.txt", "input20K.txt"})
    void testNearestReturnsTheSamePointWhenTheNeighbourIsUnique(String fixture) {
        List<Point2D> points = readPoints(fixture);
        KdTree tree = build(points);
        PointSET reference = brute(points);

        for (Point2D query : queryPoints(100)) {
            Point2D fromBruteForce = reference.nearest(query);
            double best = query.distanceTo(fromBruteForce);

            long atThatDistance = points.stream().filter(p -> query.distanceTo(p) == best).count();
            Assertions.assertEquals(
                    1, atThatDistance,
                    () -> fixture + ": " + query + " no longer has a unique nearest neighbour"
            );

            Assertions.assertEquals(fromBruteForce, tree.nearest(query), () -> fixture + ": " + query);
        }
    }

    /** Querying with a point that is in the set must return that point, at distance zero. */
    @ParameterizedTest(name = "{0}: nearest to a member is itself")
    @MethodSource("smallFixtures")
    void testNearestToAPointInTheSetIsThatPoint(String fixture, int ignoredSize) {
        List<Point2D> points = readPoints(fixture);
        KdTree tree = build(points);

        points.forEach(point -> Assertions.assertEquals(point, tree.nearest(point), fixture));
    }

    /**
     * The tie-break fixtures. Every point in {@code vertical7.txt} has x = 0.3 and every point in
     * {@code horizontal8.txt} has y = 0.5, so at alternating levels of the tree the splitting
     * coordinate is equal for every comparison and {@code KdTree.compare} has to decide on the
     * other one. Getting that wrong loses points off the tree, which the size and containment
     * assertions here would catch.
     */
    @ParameterizedTest(name = "{0} degenerates onto a single line")
    @ValueSource(strings = {"vertical7.txt", "horizontal8.txt"})
    void testPointsSharingASplittingCoordinateAreAllReachable(String fixture) {
        List<Point2D> points = readPoints(fixture);
        Set<Point2D> distinct = new HashSet<>(points);

        KdTree tree = build(points);

        Assertions.assertEquals(distinct.size(), tree.size(), fixture);
        distinct.forEach(point -> Assertions.assertTrue(tree.contains(point), () -> fixture + ": " + point));
        Assertions.assertEquals(
                distinct,
                toSet(tree.range(new RectHV(0.0, 0.0, 1.0, 1.0))),
                fixture + ": every point is still in range"
        );
    }

    /** All of vertical7 lies on x = 0.3, so a thin band around that line holds the lot. */
    @Test
    void testVerticalFixtureLiesEntirelyOnOneVerticalLine() {
        List<Point2D> points = readPoints("vertical7.txt");
        points.forEach(point -> Assertions.assertEquals(0.3, point.x(), 1e-9));

        KdTree tree = build(points);

        Assertions.assertEquals(7, toList(tree.range(new RectHV(0.29, 0.0, 0.31, 1.0))).size());
        Assertions.assertTrue(toList(tree.range(new RectHV(0.31, 0.0, 1.0, 1.0))).isEmpty());
    }

    /** And all of horizontal8 lies on y = 0.5. */
    @Test
    void testHorizontalFixtureLiesEntirelyOnOneHorizontalLine() {
        List<Point2D> points = readPoints("horizontal8.txt");
        points.forEach(point -> Assertions.assertEquals(0.5, point.y(), 1e-9));

        KdTree tree = build(points);

        Assertions.assertEquals(8, toList(tree.range(new RectHV(0.0, 0.49, 1.0, 0.51))).size());
        Assertions.assertTrue(toList(tree.range(new RectHV(0.0, 0.51, 1.0, 1.0))).isEmpty());
    }

    /**
     * {@code circle4.txt} is the smallest fixture and its four points sit at the compass points of
     * the unit square, which makes the expected answers checkable by hand rather than by oracle.
     */
    @Test
    void testTheFourCompassPointsOfTheSmallestFixture() {
        KdTree tree = build(readPoints("circle4.txt"));

        Assertions.assertEquals(4, tree.size());
        Assertions.assertTrue(tree.contains(new Point2D(0.0, 0.5)));
        Assertions.assertTrue(tree.contains(new Point2D(0.5, 1.0)));
        Assertions.assertTrue(tree.contains(new Point2D(0.5, 0.0)));
        Assertions.assertTrue(tree.contains(new Point2D(1.0, 0.5)));

        Assertions.assertEquals(new Point2D(0.5, 0.0), tree.nearest(new Point2D(0.5, 0.1)));
        Assertions.assertEquals(new Point2D(0.0, 0.5), tree.nearest(new Point2D(0.1, 0.5)));

        Assertions.assertEquals(
                Set.of(new Point2D(0.5, 0.0)),
                toSet(tree.range(new RectHV(0.0, 0.0, 1.0, 0.25)))
        );
    }

    /** An empty rectangle in a populated tree reports nothing, and must not throw. */
    @ParameterizedTest(name = "{0}: a rectangle outside every point is empty")
    @MethodSource("smallFixtures")
    void testRangeOverAnEmptyRegionReportsNothing(String fixture, int ignoredSize) {
        KdTree tree = build(readPoints(fixture));
        PointSET reference = brute(readPoints(fixture));

        // a degenerate rectangle at a coordinate no fixture uses
        RectHV empty = new RectHV(0.123456, 0.654321, 0.123456, 0.654321);

        Assertions.assertEquals(toSet(reference.range(empty)), toSet(tree.range(empty)), fixture);
    }

    private static List<Point2D> readPoints(String fixture) {
        In in = ResourceFiles.open(KdTreePointFilesTest.class, fixture);
        List<Point2D> points = new ArrayList<>();
        while (!in.isEmpty()) {
            points.add(new Point2D(in.readDouble(), in.readDouble()));
        }
        in.close();
        return points;
    }

    private static KdTree build(List<Point2D> points) {
        KdTree tree = new KdTree();
        points.forEach(tree::insert);
        return tree;
    }

    private static PointSET brute(List<Point2D> points) {
        PointSET set = new PointSET();
        points.forEach(set::insert);
        return set;
    }

    /** Deterministic probes spread over the unit square, plus its corners and centre. */
    private static List<Point2D> queryPoints(int count) {
        List<Point2D> queries = new ArrayList<>(List.of(
                new Point2D(0.0, 0.0), new Point2D(1.0, 1.0),
                new Point2D(0.0, 1.0), new Point2D(1.0, 0.0),
                new Point2D(0.5, 0.5)
        ));
        Random random = new Random(SEED);
        for (int i = queries.size(); i < count; i++) {
            queries.add(new Point2D(random.nextDouble(), random.nextDouble()));
        }
        return queries;
    }

    /**
     * Rectangles chosen to straddle the splitting lines a kd-tree builds: quadrants, thin bands
     * across the middle in both directions, degenerate lines and points, and the whole square.
     */
    private static List<RectHV> queryRectangles() {
        return List.of(
                new RectHV(0.0, 0.0, 1.0, 1.0),
                new RectHV(0.0, 0.0, 0.5, 0.5),
                new RectHV(0.5, 0.5, 1.0, 1.0),
                new RectHV(0.0, 0.5, 0.5, 1.0),
                new RectHV(0.5, 0.0, 1.0, 0.5),
                new RectHV(0.25, 0.25, 0.75, 0.75),
                new RectHV(0.49, 0.0, 0.51, 1.0),   // thin vertical band
                new RectHV(0.0, 0.49, 1.0, 0.51),   // thin horizontal band
                new RectHV(0.3, 0.3, 0.3, 0.3),     // a single point
                new RectHV(0.3, 0.0, 0.3, 1.0),     // a vertical line
                new RectHV(0.0, 0.5, 1.0, 0.5),     // a horizontal line
                new RectHV(0.9, 0.9, 1.0, 1.0)      // a far corner
        );
    }

    private static Set<Point2D> toSet(Iterable<Point2D> points) {
        return new HashSet<>(toList(points));
    }

    private static List<Point2D> toList(Iterable<Point2D> points) {
        List<Point2D> collected = new ArrayList<>();
        points.forEach(collected::add);
        return collected;
    }
}
