import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class BruteTest {

    @Test
    void testFindsEveryCollinearQuadruple() {
        List<Point> points = List.of(
                new Point(3000, 3000),
                new Point(1000, 1000),
                new Point(4000, 4000),
                new Point(2000, 2000),
                new Point(1000, 2000)
        );

        List<List<String>> segments = Brute.findSegments(points).stream()
                .map(segment -> segment.stream().map(Point::toString).toList())
                .toList();

        Assertions.assertEquals(
                List.of(List.of("(1000, 1000)", "(2000, 2000)", "(3000, 3000)", "(4000, 4000)")),
                segments
        );
    }

    @Test
    void testReturnsNoSegmentsWhenFewerThanFourPointsAreCollinear() {
        List<Point> points = List.of(
                new Point(0, 0),
                new Point(1, 1),
                new Point(2, 3),
                new Point(3, 2)
        );

        Assertions.assertTrue(Brute.findSegments(points).isEmpty());
    }

    @Test
    void testRejectsNullInput() {
        Assertions.assertThrows(NullPointerException.class, () -> Brute.findSegments(null));
        Assertions.assertThrows(
                NullPointerException.class,
                () -> Brute.findSegments(
                        java.util.Arrays.asList(new Point(0, 0), null, new Point(1, 1), new Point(2, 2))
                )
        );
    }
}
