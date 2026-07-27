import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives {@link Brute} and {@link Fast} from the point files in {@code src/test/resources}.
 * <p>
 * Several fixture names encode the expected answer: {@code horizontalN.txt} and
 * {@code verticalN.txt} hold 4N points arranged as N segments of exactly four, and
 * {@code randomN.txt} holds N points with no four of them collinear. Where every segment is
 * exactly four points long the two algorithms must agree, which makes those files a
 * ready-made cross-check.
 * <p>
 * {@code Brute} is O(n^4), so it only runs here on fixtures of at most 400 points
 * ({@code input400.txt} takes 48 ms at that size).
 * Larger files go through {@code Fast} alone and assert maximality instead: no other point in
 * the file may lie on the line of a reported segment, which is verifiable from the input without
 * a second algorithm to compare against.
 * <p>
 * The very large files are left out entirely. {@code Fast} needs 0.5 s on
 * {@code input2000.txt} and 14.5 s on {@code input10000.txt} and {@code mystery10089.txt}, and
 * {@code Brute} is infeasible on all of them, so together they would add roughly 50 s to a suite
 * that otherwise runs in about a second.
 * <p>
 * The {@code main} methods of both classes are deliberately not covered.
 * They call {@code StdDraw}, whose static initializer throws {@code ExceptionInInitializerError} when
 * {@code java.awt.headless} is true, so a test touching them would pass locally and fail on any
 * headless machine. The same applies to {@code Point.draw} and {@code Point.drawTo}.
 */
class CollinearPointFilesTest {

    /** N segments of exactly four points, so Brute and Fast must report the same count. */
    static Stream<Arguments> exactlyFourPointSegmentFiles() {
        return Stream.of(
                Arguments.of("horizontal5.txt", 5),
                Arguments.of("horizontal25.txt", 25),
                Arguments.of("horizontal50.txt", 50),
                Arguments.of("horizontal75.txt", 75),
                Arguments.of("vertical5.txt", 5),
                Arguments.of("vertical25.txt", 25),
                Arguments.of("vertical50.txt", 50),
                Arguments.of("vertical75.txt", 75)
        );
    }

    @ParameterizedTest(name = "Fast finds {1} segments in {0}")
    @MethodSource("exactlyFourPointSegmentFiles")
    void testFastFindsTheSegmentCountEncodedInTheFileName(String fixture, int expectedSegments) {
        Point[] points = readPoints(fixture);

        List<List<Point>> segments = Fast.findSegments(points);

        Assertions.assertEquals(expectedSegments, segments.size(), fixture);
        segments.forEach(segment -> assertIsAValidSegment(segment, fixture));
    }

    @ParameterizedTest(name = "Brute finds {1} quadruples in {0}")
    @MethodSource("exactlyFourPointSegmentFiles")
    void testBruteFindsTheSegmentCountEncodedInTheFileName(String fixture, int expectedSegments) {
        List<Point> points = List.of(readPoints(fixture));

        List<List<Point>> segments = Brute.findSegments(points);

        Assertions.assertEquals(expectedSegments, segments.size(), fixture);
        segments.forEach(segment -> {
            Assertions.assertEquals(4, segment.size(), "Brute reports quadruples");
            assertIsAValidSegment(segment, fixture);
        });
    }

    /** These larger files only go through Fast; Brute would be O(n^4) on 400 points. */
    @ParameterizedTest(name = "Fast finds {1} segments in {0}")
    @MethodSource("largeExactlyFourPointSegmentFiles")
    void testFastHandlesTheLargerStructuredFiles(String fixture, int expectedSegments) {
        List<List<Point>> segments = Fast.findSegments(readPoints(fixture));

        Assertions.assertEquals(expectedSegments, segments.size(), fixture);
        segments.forEach(segment -> assertIsAValidSegment(segment, fixture));
    }

    static Stream<Arguments> largeExactlyFourPointSegmentFiles() {
        return Stream.of(
                Arguments.of("horizontal100.txt", 100),
                Arguments.of("vertical100.txt", 100)
        );
    }

    @ParameterizedTest(name = "{0} contains no collinear quadruple")
    @ValueSource(strings = {"random23.txt", "random38.txt", "random91.txt", "random152.txt"})
    void testRandomPointFilesContainNoCollinearQuadruples(String fixture) {
        Point[] points = readPoints(fixture);

        Assertions.assertTrue(Fast.findSegments(points).isEmpty(), fixture + " via Fast");
        Assertions.assertTrue(Brute.findSegments(List.of(points)).isEmpty(), fixture + " via Brute");
    }

    /**
     * The two algorithms report different things - Brute lists every collinear quadruple, Fast
     * lists maximal segments - but they must be consistent: each quadruple Brute finds has to be
     * four points drawn from one of Fast's segments.
     */
    @ParameterizedTest(name = "Brute quadruples in {0} all sit inside a Fast segment")
    @ValueSource(strings = {
            "input6.txt", "input8.txt", "input9.txt", "input10.txt", "input20.txt",
            "input40.txt", "input48.txt", "input50.txt", "input56.txt", "input80.txt",
            "input100.txt", "equidistant.txt", "inarow.txt",
            "grid4x4.txt", "grid5x5.txt", "grid6x6.txt",
            "input150.txt", "input200.txt", "input250.txt", "input299.txt", "input300.txt",
            "input350.txt", "input400.txt"
    })
    void testBruteAndFastAgree(String fixture) {
        Point[] points = readPoints(fixture);

        List<List<Point>> maximalSegments = Fast.findSegments(points);
        List<List<Point>> quadruples = Brute.findSegments(List.of(points));

        maximalSegments.forEach(segment -> assertIsAValidSegment(segment, fixture));
        quadruples.forEach(quadruple -> assertIsAValidSegment(quadruple, fixture));

        List<Set<String>> maximalAsSets = maximalSegments.stream()
                .map(CollinearPointFilesTest::asPointNames)
                .toList();

        for (List<Point> quadruple : quadruples) {
            Set<String> names = asPointNames(quadruple);
            Assertions.assertTrue(
                    maximalAsSets.stream().anyMatch(segment -> segment.containsAll(names)),
                    () -> fixture + ": Brute found " + names + " but no Fast segment contains it"
            );
        }
    }

    /**
     * The large files, where Brute is not an option: 1000+ points would be O(n^4).
     *
     * <p>Instead of a cross-check against Brute these assert maximality directly - no other point
     * in the file may lie on the line of a reported segment - which is verifiable from the input
     * alone. {@code rs1423.txt} matters most here: it is the default filename in both
     * {@code Brute.main} and {@code Fast.main}, so it is the one input the module ships pointing at.
     *
     * <p>The expected counts are observed values recorded as a regression baseline, not
     * independently derived the way the {@code horizontalN} counts are.
     */
    @ParameterizedTest(name = "Fast finds {1} maximal segments in {0}")
    @MethodSource("largeFastOnlyFiles")
    void testFastProducesMaximalSegmentsOnLargeFiles(String fixture, int expectedSegments) {
        Point[] points = readPoints(fixture);

        List<List<Point>> segments = Fast.findSegments(points);

        Assertions.assertEquals(expectedSegments, segments.size(), fixture);
        segments.forEach(segment -> assertIsAValidSegment(segment, fixture));
        assertSegmentsAreMaximal(segments, points, fixture);
        Assertions.assertEquals(
                segments.size(),
                segments.stream().map(CollinearPointFilesTest::asPointNames).distinct().count(),
                fixture + ": no duplicated segments"
        );
    }

    static Stream<Arguments> largeFastOnlyFiles() {
        return Stream.of(
                Arguments.of("input1000.txt", 0),
                Arguments.of("kw1260.txt", 288),
                Arguments.of("rs1423.txt", 443)
        );
    }

    @Test
    void testFastReportsEachMaximalSegmentOnlyOnce() {
        // nine collinear points: Brute enumerates all C(9,4) = 126 quadruples,
        // Fast must collapse them into the single maximal segment
        Point[] points = readPoints("input9.txt");

        List<List<Point>> segments = Fast.findSegments(points);

        Assertions.assertEquals(1, segments.size());
        Assertions.assertEquals(9, segments.getFirst().size());
        Assertions.assertEquals(126, Brute.findSegments(List.of(points)).size());
    }

    @Test
    void testFastDoesNotReportSubsegmentsOfALongerLine() {
        List<List<Point>> segments = Fast.findSegments(readPoints("inarow.txt"));

        Assertions.assertEquals(5, segments.size());
        Assertions.assertEquals(
                segments.size(),
                segments.stream().map(CollinearPointFilesTest::asPointNames).distinct().count(),
                "no duplicated segments"
        );
    }

    /** A single point, two points and three points can never form a segment. */
    @ParameterizedTest(name = "{0} yields no segments")
    @ValueSource(strings = {"input1.txt", "input2.txt", "input3.txt"})
    void testFilesWithFewerThanFourPointsYieldNothing(String fixture) {
        Point[] points = readPoints(fixture);

        Assertions.assertTrue(Fast.findSegments(points).isEmpty());
        Assertions.assertTrue(Brute.findSegments(List.of(points)).isEmpty());
    }

    /**
     * Points in a segment must be genuinely collinear, in ascending order, and there must be at
     * least four of them.
     */
    private static void assertIsAValidSegment(List<Point> segment, String fixture) {
        Assertions.assertTrue(segment.size() >= 4, () -> fixture + ": segment shorter than four points");

        Point first = segment.getFirst();
        double slope = first.slopeTo(segment.get(1));
        for (int index = 2; index < segment.size(); index++) {
            int position = index;
            Assertions.assertEquals(
                    slope,
                    first.slopeTo(segment.get(position)),
                    () -> fixture + ": point " + position + " of " + asPointNames(segment) + " is not collinear"
            );
        }

        for (int position = 1; position < segment.size(); position++) {
            Assertions.assertTrue(
                    segment.get(position - 1).compareTo(segment.get(position)) < 0,
                    () -> fixture + ": segment " + asPointNames(segment) + " is not in ascending order"
            );
        }
    }

    /**
     * A segment is maximal when no further point in the file lies on its line. Points that
     * duplicate a coordinate already in the segment are ignored, since they add no length.
     */
    private static void assertSegmentsAreMaximal(List<List<Point>> segments, Point[] allPoints, String fixture) {
        for (List<Point> segment : segments) {
            Point first = segment.getFirst();
            double slope = first.slopeTo(segment.get(1));
            Set<String> onSegment = asPointNames(segment);

            for (Point candidate : allPoints) {
                if (onSegment.contains(candidate.toString())) {
                    continue;
                }
                Assertions.assertNotEquals(
                        slope,
                        first.slopeTo(candidate),
                        () -> fixture + ": " + candidate + " lies on the line of "
                                + asPointNames(segment) + ", so that segment is not maximal"
                );
            }
        }
    }

    private static Set<String> asPointNames(List<Point> segment) {
        return segment.stream().map(Point::toString).collect(HashSet::new, Set::add, Set::addAll);
    }

    private static Point[] readPoints(String fixture) {
        try (InputStream stream = CollinearPointFilesTest.class.getResourceAsStream("/" + fixture)) {
            Assertions.assertNotNull(stream, "missing test resource: " + fixture);
            Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8);
            int count = scanner.nextInt();
            List<Point> points = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                points.add(new Point(scanner.nextInt(), scanner.nextInt()));
            }
            return points.toArray(new Point[0]);
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + fixture, e);
        }
    }
}
