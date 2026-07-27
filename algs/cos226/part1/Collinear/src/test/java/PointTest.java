import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Comparator;

/**
 * @author Lipatov Nikita
 */
public class PointTest {

    @Test
    public void testSlopeToNoDibByZero() {
        Point it = new Point(1, 1);
        Point that = new Point(1, 1);
        Assertions.assertEquals(Double.NEGATIVE_INFINITY, it.slopeTo(that));
    }

    @Test
    public void testSlopeToNegativeInfinity() {
        Point it = new Point(0, 0);
        Point that = new Point(0, 0);
        double slope = it.slopeTo(that);
        Assertions.assertEquals(Double.NEGATIVE_INFINITY, slope, 0.1);
    }

    @Test
    public void testSlopeToPositiveInfinityCase1() {
        Point it = new Point(0, 0);
        Point that = new Point(0, 4);
        double slope = it.slopeTo(that);
        Assertions.assertEquals(Double.POSITIVE_INFINITY, slope, 0.1);
    }

    @Test
    public void testSlopeToPositiveInfinityCase2() {
        Point it = new Point(0, 0);
        Point that = new Point(0, -4);
        double slope = it.slopeTo(that);
        Assertions.assertEquals(Double.POSITIVE_INFINITY, slope, 0.1);
    }

    @Test
    public void testSlopeToZero() {
        Point it = new Point(0, 0);
        Point that = new Point(4, 0);
        double slope = it.slopeTo(that);
        Assertions.assertEquals(0.0, slope, 0.1);
    }

    @Test
    public void testSlopeToInfinity() {
        Point it = new Point(0, 0);
        Point that = new Point(-4, 0);
        double slope = it.slopeTo(that);
        Assertions.assertEquals(0.0, slope, 0.1);
    }

    @Test
    public void testCompareToLessCase1() {
        Point it = new Point(-1, -1);
        Point that = new Point(0, 0);
        int res = it.compareTo(that);
        Assertions.assertEquals(-1, res);
    }

    @Test
    public void testCompareToLessCase2() {
        Point it = new Point(-1, -1);
        Point that = new Point(-1, 0);
        int res = it.compareTo(that);
        Assertions.assertEquals(-1, res);
    }

    @Test
    public void testCompareToEqualsCase1() {
        Point it = new Point(1, 1);
        Point that = new Point(1, 1);
        int res = it.compareTo(that);
        Assertions.assertEquals(0, res);
    }

    @Test
    public void testCompareToEqualsCase2() {
        Point it = new Point(1, 0);
        Point that = new Point(1, 0);
        int res = it.compareTo(that);
        Assertions.assertEquals(0, res);
    }

    @Test
    public void testCompareToEqualsCase3() {
        Point it = new Point(0, 1);
        Point that = new Point(0, 1);
        int res = it.compareTo(that);
        Assertions.assertEquals(0, res);
    }

    @Test
    public void testCompareToGreaterCase1() {
        Point it = new Point(0, 1);
        Point that = new Point(0, 0);
        int res = it.compareTo(that);
        Assertions.assertEquals(1, res);
    }

    @Test
    public void testCompareToGreaterCase2() {
        Point it = new Point(0, 0);
        Point that = new Point(0, -1);
        int res = it.compareTo(that);
        Assertions.assertEquals(1, res);
    }

    @Test
    public void testSlopeOrder() {
        Point it = new Point(0, 0);
        Point q1 = new Point(-1, 3);
        Point q2 = new Point(-1, -3);

        Comparator<Point> comparator = it.SLOPE_ORDER;
        Assertions.assertTrue(comparator.compare(q1, q2) < 0);
    }
}
