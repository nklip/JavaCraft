/**
 * Common contract for mutable sets of two-dimensional points.
 * <p>
 * ┌────────────────┬─────────────┬────────────────────┬──────────────┐
 * │ Operation      │ PointSET    │ KdTree average     │ KdTree worst │
 * ├────────────────┼─────────────┼────────────────────┼──────────────┤
 * │ insert         │ O(log n)    │ O(log n)           │ O(n)         │
 * │ contains       │ O(log n)    │ O(log n)           │ O(n)         │
 * │ nearest        │ O(n)        │ About O(log n)     │ O(n)         │
 * │ range          │ O(n + k)    │ O(√n + k)          │ O(n + k)     │
 * │ draw           │ O(n)        │ O(n)               │ O(n)         │
 * │ size / isEmpty │ O(1)        │ O(1)               │ O(1)         │
 * │ Space          │ O(n)        │ O(n)               │ O(n)         │
 * └────────────────┴─────────────┴────────────────────┴──────────────┘
 */
public interface SpatialPointSet {

    /**
     * @return whether this set contains no points
     */
    boolean isEmpty();

    /**
     * @return the number of distinct points
     */
    int size();

    /**
     * Adds a point unless it is already present.
     *
     * @param point point to add
     * @throws NullPointerException if {@code point} is {@code null}
     */
    void insert(Point2D point);

    /**
     * @param point point to find
     * @return whether the point is present
     * @throws NullPointerException if {@code point} is {@code null}
     */
    boolean contains(Point2D point);

    /**
     * Draws the stored points.
     */
    void draw();

    /**
     * Finds every point inside a rectangle.
     *
     * @param rectangle query rectangle
     * @return points inside the rectangle
     * @throws NullPointerException if {@code rectangle} is {@code null}
     */
    Iterable<Point2D> range(RectHV rectangle);

    /**
     * Finds the nearest point.
     *
     * @param point query point
     * @return the nearest point, or {@code null} when this set is empty
     * @throws NullPointerException if {@code point} is {@code null}
     */
    Point2D nearest(Point2D point);
}
