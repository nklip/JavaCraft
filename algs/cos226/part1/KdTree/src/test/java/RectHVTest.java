import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RectHVTest {

    @Test
    void testRejectsInvertedCoordinates() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RectHV(0.8, 0.2, 0.1, 0.9));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RectHV(0.1, 0.9, 0.8, 0.2));
    }

    @Test
    void testExposesDimensionsAndCoordinates() {
        RectHV rectangle = new RectHV(0.1, 0.2, 0.8, 0.9);

        Assertions.assertEquals(0.1, rectangle.xmin());
        Assertions.assertEquals(0.2, rectangle.ymin());
        Assertions.assertEquals(0.8, rectangle.xmax());
        Assertions.assertEquals(0.9, rectangle.ymax());
        Assertions.assertEquals(0.7, rectangle.width(), 1.0e-10);
        Assertions.assertEquals(0.7, rectangle.height(), 1.0e-10);
    }

    @Test
    void testDetectsContainmentAndIntersectionIncludingBoundaries() {
        RectHV rectangle = new RectHV(0.1, 0.2, 0.8, 0.9);

        Assertions.assertTrue(rectangle.contains(new Point2D(0.1, 0.2)));
        Assertions.assertTrue(rectangle.intersects(new RectHV(0.8, 0.9, 1.0, 1.0)));
        Assertions.assertFalse(rectangle.contains(new Point2D(0.0, 0.5)));
        Assertions.assertFalse(rectangle.intersects(new RectHV(0.81, 0.91, 1.0, 1.0)));
    }

    @Test
    void testCalculatesDistanceToClosestPoint() {
        RectHV rectangle = new RectHV(0.2, 0.2, 0.8, 0.8);

        Assertions.assertEquals(0.0, rectangle.distanceTo(new Point2D(0.5, 0.5)));
        Assertions.assertEquals(0.01, rectangle.distanceSquaredTo(new Point2D(0.1, 0.5)), 1.0e-10);
        Assertions.assertEquals(Math.sqrt(0.02), rectangle.distanceTo(new Point2D(0.9, 0.9)), 1.0e-10);
    }

    @Test
    void testImplementsValueEquality() {
        RectHV rectangle = new RectHV(0.1, 0.2, 0.8, 0.9);
        RectHV equalRectangle = new RectHV(0.1, 0.2, 0.8, 0.9);

        Assertions.assertEquals(rectangle, equalRectangle);
        Assertions.assertEquals(rectangle.hashCode(), equalRectangle.hashCode());
        Assertions.assertNotEquals(new RectHV(0.1, 0.2, 0.8, 1.0), rectangle);
        Assertions.assertNotEquals(null, rectangle);
        Assertions.assertEquals("[0.1, 0.8] x [0.2, 0.9]", rectangle.toString());
    }

    /**
     * {@code equals} is called directly here rather than through {@code assertNotEquals}, which
     * compares the other way round: {@code assertNotEquals("s", rect)} runs {@code String.equals}
     * and {@code assertNotEquals(null, rect)} is short-circuited by JUnit, so neither reaches the
     * identity, null and class checks at the top of this method.
     */
    @Test
    void testEqualsRejectsEachKindOfMismatchInTurn() {
        RectHV rectangle = new RectHV(0.1, 0.2, 0.8, 0.9);

        Assertions.assertEquals(rectangle, rectangle, "identity");
        Assertions.assertNotEquals(null, rectangle, "null");

        // one coordinate differs at a time, so no single comparison can carry the whole method
        Assertions.assertNotEquals(new RectHV(0.0, 0.2, 0.8, 0.9), rectangle, "xmin");
        Assertions.assertNotEquals(new RectHV(0.1, 0.0, 0.8, 0.9), rectangle, "ymin");
        Assertions.assertNotEquals(new RectHV(0.1, 0.2, 0.9, 0.9), rectangle, "xmax");
        Assertions.assertNotEquals(new RectHV(0.1, 0.2, 0.8, 1.0), rectangle, "ymax");
        Assertions.assertEquals(new RectHV(0.1, 0.2, 0.8, 0.9), rectangle, "all four match");
    }
}
