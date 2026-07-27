import java.util.Set;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Brute-force approach.
 * <p>
 * Time complexity: O(n)
 * <p>
 * @author Lipatov Nikita
 */
public class PointSET implements SpatialPointSet {
    private final Set<Point2D> points;

    // construct an empty set of points
    public PointSET() {
        points = new TreeSet<>();
    }

    // is the set empty?
    @Override
    public boolean isEmpty() {
        return points.isEmpty();
    }

    // number of points in the set
    @Override
    public int size() {
        return points.size();
    }

    // add the point to the set (if it is not already in the set)
    @Override
    public void insert(Point2D p) {
        Objects.requireNonNull(p, "point");
        points.add(p);
    }

    // does the set contain point p?
    @Override
    public boolean contains(Point2D p) {
        Objects.requireNonNull(p, "point");
        return points.contains(p);
    }

    // draw all points to standard draw
    @Override
    public void draw() {
        for (Point2D point : points) {
            point.draw();
        }
    }

    // all points that are inside the rectangle
    @Override
    public Iterable<Point2D> range(RectHV rect) {
        Objects.requireNonNull(rect, "rectangle");
        List<Point2D> rangePoints = new ArrayList<>();

        for (Point2D point : points) {
            if (rect.contains(point)) {
                rangePoints.add(point);
            }
        }
        return rangePoints;
    }

    // a nearest neighbor in the set to point p; null if the set is empty
    @Override
    public Point2D nearest(Point2D p) {
        Objects.requireNonNull(p, "point");
        if (isEmpty()) {
            return null;
        }

        Point2D nearestNeighbor = null;
        double shortestHypotenuse = 2.0;

        for (Point2D point : points) {
            double tempHypotenuse = p.distanceTo(point);
            if (nearestNeighbor == null || tempHypotenuse < shortestHypotenuse) {
                nearestNeighbor = point;
                shortestHypotenuse = tempHypotenuse;
            }
        }
        return nearestNeighbor;
    }

}
