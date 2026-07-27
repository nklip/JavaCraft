import java.util.Comparator;

/**
 * @author Lipatov Nikita
 */
public class Point implements Comparable<Point> {

    private static final double POSITIVE_ZERO = 0.0;

    // compare points by slope to this point
    public final Comparator<Point> SLOPE_ORDER;

    private final int x;                              // x coordinate
    private final int y;                              // y coordinate

    // construct the point (x, y)
    public Point(int x, int y) {
        /* DO NOT MODIFY */
        this.x = x;
        this.y = y;
        this.SLOPE_ORDER = new SlopeComparator();
    }

    // draw this point
    public void draw() {
        /* DO NOT MODIFY */
        StdDraw.point(x, y);
    }

    // draw the line segment from this point to that point
    public void drawTo(Point that) {
        /* DO NOT MODIFY */
        StdDraw.line(this.x, this.y, that.x, that.y);
    }

    // return string representation of this point
    @Override
    public String toString() {
        /* DO NOT MODIFY */
        return "(" + x + ", " + y + ")";
    }

    /**
     * The compareTo() method should compare points by their y-coordinates, breaking
     * ties by their x-coordinates. Formally, the invoking point (x0, y0) is less
     * than the argument point (x1, y1) if and only if either y0 < y1 or if y0 = y1 and x0 < x1.
     */
    @Override
    public int compareTo(Point that) {
        /* YOUR CODE HERE */
        if ((this.y < that.y) || ((this.y == that.y) && (this.x < that.x))) {
            return -1;
        } else if ((this.y == that.y) && (this.x == that.x)) {
            return 0;
        } else {
            return +1;
        }
    }

    /**
     * The slopeTo() method should return the slope between the invoking point (x0, y0)
     * and the argument point (x1, y1), which is given by the formula (y1 − y0) / (x1 − x0).
     * Treat the slope of a horizontal line segment as positive zero;
     * treat the slope of a vertical   line segment as positive infinity;
     * treat the slope of a degenerate line segment (between a point and itself) as negative infinity.
     */
    public double slopeTo(Point that) {
        /* YOUR CODE HERE */

        int difY = that.y - this.y;
        int difX = that.x - this.x;
        if (difX == 0 && difY == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        if (difY == 0) { // horizontal line segments
            return Point.POSITIVE_ZERO;
        }
        if (difX == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (double) difY / (double) difX;
    }


    /**
     * The SLOPE_ORDER comparator should compare points by the slopes they make with the invoking point (x0, y0).
     * Formally, the point (x1, y1) is less than the point (x2, y2)
     * if and only if the slope (y1 − y0) / (x1 − x0) is less than the slope (y2 − y0) / (x2 − x0).
     * Treat horizontal, vertical, and degenerate line segments as in the slopeTo() method.
     */
    private class SlopeComparator implements Comparator<Point> {

        @Override
        public int compare(Point q, Point r) {
            double psq = Point.this.slopeTo(q);
            double psr = Point.this.slopeTo(r);

            return Double.compare(psq, psr);
        }

    }

}
