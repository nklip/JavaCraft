import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class PointSETTest {

    private PointSET points;

    @BeforeEach
    void setUp() {
        points = new PointSET();
    }

    @Test
    void testStartsEmpty() {
        Assertions.assertTrue(points.isEmpty());
        Assertions.assertEquals(0, points.size());
        Assertions.assertNull(points.nearest(new Point2D(0.5, 0.5)));
        Assertions.assertFalse(points.range(new RectHV(0.0, 0.0, 1.0, 1.0)).iterator().hasNext());
        Assertions.assertDoesNotThrow(points::draw);
    }

    @Test
    void testInsertsPointsAndIgnoresDuplicates() {
        Point2D point = new Point2D(0.2, 0.3);

        points.insert(point);
        points.insert(point);

        Assertions.assertEquals(1, points.size());
        Assertions.assertTrue(points.contains(point));
        Assertions.assertFalse(points.contains(new Point2D(0.3, 0.2)));
    }

    @Test
    void testReturnsOnlyPointsInsideRange() {
        populate(points);

        Set<Point2D> actual = new HashSet<>();
        points.range(new RectHV(0.0, 0.0, 0.5, 0.5)).forEach(actual::add);

        Assertions.assertEquals(
                Set.of(new Point2D(0.5, 0.4), new Point2D(0.2, 0.3)),
                actual
        );
    }

    @Test
    void testFindsNearestPoint() {
        populate(points);

        Assertions.assertEquals(new Point2D(0.7, 0.2), points.nearest(new Point2D(0.65, 0.25)));
    }

    @Test
    void testRejectsNullArguments() {
        Assertions.assertThrows(NullPointerException.class, () -> points.insert(null));
        Assertions.assertThrows(NullPointerException.class, () -> points.contains(null));
        Assertions.assertThrows(NullPointerException.class, () -> points.range(null));
        Assertions.assertThrows(NullPointerException.class, () -> points.nearest(null));
    }

    private static void populate(PointSET points) {
        points.insert(new Point2D(0.7, 0.2));
        points.insert(new Point2D(0.5, 0.4));
        points.insert(new Point2D(0.2, 0.3));
        points.insert(new Point2D(0.4, 0.7));
        points.insert(new Point2D(0.9, 0.6));
    }
}
