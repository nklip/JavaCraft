import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SpatialPointSetTest {

    static Stream<Arguments> implementations() {
        return Stream.of(
                Arguments.of("PointSET", (Supplier<SpatialPointSet>) PointSET::new),
                Arguments.of("KdTree", (Supplier<SpatialPointSet>) KdTree::new)
        );
    }

    @ParameterizedTest(name = "{0} satisfies the empty-set contract")
    @MethodSource("implementations")
    void testEmptySetContract(String implementation, Supplier<SpatialPointSet> factory) {
        SpatialPointSet points = factory.get();

        Assertions.assertTrue(points.isEmpty(), implementation);
        Assertions.assertEquals(0, points.size());
        Assertions.assertNull(points.nearest(new Point2D(0.5, 0.5)));
        Assertions.assertFalse(
                points.range(new RectHV(0.0, 0.0, 1.0, 1.0)).iterator().hasNext()
        );
        Assertions.assertDoesNotThrow(points::draw);
    }

    @ParameterizedTest(name = "{0} satisfies the query contract")
    @MethodSource("implementations")
    void testMutationAndQueryContract(String implementation, Supplier<SpatialPointSet> factory) {
        SpatialPointSet points = factory.get();
        Point2D lowerLeft = new Point2D(0.2, 0.3);
        Point2D middle = new Point2D(0.5, 0.4);
        Point2D upperRight = new Point2D(0.8, 0.9);

        points.insert(lowerLeft);
        points.insert(middle);
        points.insert(upperRight);
        points.insert(lowerLeft);

        Assertions.assertFalse(points.isEmpty(), implementation);
        Assertions.assertEquals(3, points.size());
        Assertions.assertTrue(points.contains(lowerLeft));
        Assertions.assertFalse(points.contains(new Point2D(0.1, 0.1)));

        Set<Point2D> inRange = new HashSet<>();
        points.range(new RectHV(0.0, 0.0, 0.5, 0.5)).forEach(inRange::add);
        Assertions.assertEquals(Set.of(lowerLeft, middle), inRange);
        Assertions.assertEquals(middle, points.nearest(new Point2D(0.55, 0.45)));
    }

    @ParameterizedTest(name = "{0} rejects null queries")
    @MethodSource("implementations")
    void testNullContract(String implementation, Supplier<SpatialPointSet> factory) {
        SpatialPointSet points = factory.get();

        Assertions.assertThrows(
                NullPointerException.class,
                () -> points.insert(null),
                implementation
        );
        Assertions.assertThrows(NullPointerException.class, () -> points.contains(null));
        Assertions.assertThrows(NullPointerException.class, () -> points.range(null));
        Assertions.assertThrows(NullPointerException.class, () -> points.nearest(null));
    }
}
