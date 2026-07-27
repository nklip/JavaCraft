import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class FastTest {

    @Test
    void testFindsOneMaximalSegmentWithoutDuplicates() {
        Point[] points = {
                new Point(5000, 5000),
                new Point(1000, 1000),
                new Point(4000, 4000),
                new Point(2000, 2000),
                new Point(3000, 3000),
                new Point(1000, 2000)
        };

        List<List<String>> segments = Fast.findSegments(points).stream()
                .map(segment -> segment.stream().map(Point::toString).toList())
                .toList();

        Assertions.assertEquals(
                List.of(List.of(
                        "(1000, 1000)",
                        "(2000, 2000)",
                        "(3000, 3000)",
                        "(4000, 4000)",
                        "(5000, 5000)"
                )),
                segments
        );
    }

    @Test
    void testFindsSegmentsWithDifferentSlopes() {
        Point[] points = {
                new Point(0, 0),
                new Point(1, 1),
                new Point(2, 2),
                new Point(3, 3),
                new Point(0, 1),
                new Point(0, 2),
                new Point(0, 3)
        };

        Assertions.assertEquals(2, Fast.findSegments(points).size());
    }

    @Test
    void testRejectsNullInput() {
        Assertions.assertThrows(NullPointerException.class, () -> Fast.findSegments(null));
        Assertions.assertThrows(
                NullPointerException.class,
                () -> Fast.findSegments(new Point[]{new Point(0, 0), null})
        );
    }
}
