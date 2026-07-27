import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * This algorithm improves searches by pruning spatial regions, typically much faster than O(n).
 *
 * @author Lipatov Nikita
 */
public class KdTree implements SpatialPointSet {
    private static final boolean IS_VERTICAL = true;
    private static final int IS_RIGHT =  1;
    private static final int IS_LEFT  = -1;
    private static final int IS_ZERO  =  0;

    private int size = 0;
    private Node root;
    private Point2D nearest;
    private double shortestWay = 2.0;

    @Override
    public boolean isEmpty() {
        return root == null;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void insert(Point2D p) {
        Objects.requireNonNull(p, "point");
        if (isEmpty()) {
            root = new Node(p, new RectHV(0, 0, 1, 1));
            size++;
        } else if (!contains(p)) {
            insertTree(root, root, p, IS_VERTICAL, IS_ZERO);
            size++;
        }
    }

    private static Node insertTree(Node root, Node parent, Point2D point, boolean isVertical, int order) {
        if (parent == null) {
            RectHV rect = createRectHV(root, !isVertical, order);
            return new Node(point, rect);
        }

        int cmp = compare(isVertical, parent.p, point);
        if (cmp < 0) {
            parent.left  = insertTree(parent, parent.left,  point, !isVertical, IS_LEFT);
        } else if (cmp > 0) {
            parent.right = insertTree(parent, parent.right, point, !isVertical, IS_RIGHT);
        }
        return parent;
    }

    private static RectHV createRectHV(Node root, boolean isVertical, int order) {
        RectHV rect;
        if (isVertical) {
            if (IS_LEFT == order) {
                rect = new RectHV(root.rect.xmin(), root.rect.ymin(), root.p.x(), root.rect.ymax());
            } else {
                rect = new RectHV(root.p.x(), root.rect.ymin(), root.rect.xmax(), root.rect.ymax());
            }
        } else { // isHorizontal
            if (IS_LEFT == order) {
                rect = new RectHV(root.rect.xmin(), root.rect.ymin(), root.rect.xmax(), root.p.y());
            } else {
                rect = new RectHV(root.rect.xmin(), root.p.y(), root.rect.xmax(), root.rect.ymax());
            }
        }
        return rect;
    }

    private static int compare(boolean isVertical, Point2D p1, Point2D p2) {
        if (isVertical) {
            double xdif = p2.x() - p1.x();
            if (xdif < 0) {
                return -1;
            } else if (xdif > 0) {
                return  1;
            } else {
                double ydif = p2.y() - p1.y();
                if (ydif < 0) {
                    return -1;
                } else if (ydif > 0) {
                    return  1;
                } else {
                    return  0;
                }
            }
        } else {
            double ydif = p2.y() - p1.y();
            if (ydif < 0) {
                return -1;
            } else if (ydif > 0) {
                return  1;
            } else {
                double xdif = p2.x() - p1.x();
                if (xdif < 0) {
                    return -1;
                } else if (xdif > 0) {
                    return  1;
                } else {
                    return  0;
                }
            }
        }
    }

    @Override
    public boolean contains(Point2D p) {
        Objects.requireNonNull(p, "point");
        return contains(root, p, IS_VERTICAL);
    }

    private static boolean contains(Node parent, Point2D point, boolean isVertical) {
        if (parent == null) {
            return false;
        } else if (parent.p.equals(point)) {
            return true;
        }
        int cmp = compare(isVertical, parent.p, point);
        if (cmp < 0) {
            return contains(parent.left,  point, !isVertical);
        } else if (cmp > 0) {
            return contains(parent.right, point, !isVertical);
        }
        return false;
    }

    @Override
    public void draw() {
        if (!isEmpty()) {
            draw(root, IS_VERTICAL);
        }
    }

    private static void draw(Node parent, boolean isVertical) {
        StdDraw.setPenRadius(0.005);
        StdDraw.setPenColor(StdDraw.BLACK);  // point
        parent.p.draw();
        if (isVertical) {
            StdDraw.setPenRadius(0.001);
            StdDraw.setPenColor(StdDraw.RED);  // vertical
            StdDraw.line(parent.p.x(), parent.rect.ymin(), parent.p.x(), parent.rect.ymax());
        } else {
            StdDraw.setPenRadius(0.001);
            StdDraw.setPenColor(StdDraw.BLUE); // horizontal
            StdDraw.line(parent.rect.xmin(), parent.p.y(), parent.rect.xmax(), parent.p.y());
        }

        if (parent.left != null) {
            draw(parent.left, !isVertical);
        }
        if (parent.right != null) {
            draw(parent.right, !isVertical);
        }
    }

    @Override
    public Iterable<Point2D> range(RectHV rectHV) {
        Objects.requireNonNull(rectHV, "rectangle");
        final List<Point2D> rangePoints = new ArrayList<>();
        if (!isEmpty()) {
            range(rangePoints, root, rectHV, IS_VERTICAL);
        }
        return rangePoints;
    }

    /**
     * A node whose rectangle misses the query prunes its whole subtree, because a child's
     * rectangle is always contained in its parent's.
     * <p>
     * There is deliberately no duplicate check on {@code list}. The traversal reaches every node
     * at most once and {@code insert} keeps the tree free of equal points, so a point can never be
     * offered twice - and the {@code List.contains} scan that used to guard against it made
     * {@code range} quadratic in the number of points it reports.
     */
    private static void range(List<Point2D> list, Node parent, RectHV rect, boolean isVertical) {
        if (!parent.rect.intersects(rect)) {
            return;
        }
        if (rect.contains(parent.p)) {
            list.add(parent.p);
        }
        if (parent.left != null) {
            range(list, parent.left,  rect, !isVertical);
        }
        if (parent.right != null) {
            range(list, parent.right, rect, !isVertical);
        }
    }

    @Override
    public Point2D nearest(Point2D p) {
        Objects.requireNonNull(p, "point");
        shortestWay = 2.0;
        nearest = null;

        findNearest(root, p);

        return nearest;
    }

    private void findNearest(Node parent, Point2D point) {
        if (parent == null) {
            return;
        }

        double currentDistance = point.distanceTo(parent.p);
        if (shortestWay > currentDistance) {
            nearest = parent.p;
            shortestWay = currentDistance;
        }
        double tempShortestWay = shortestWay;

        if (parent.left != null  && tempShortestWay >= parent.left.rect.distanceTo(point)) {
            findNearest(parent.left, point);
        }

        if (parent.right != null && tempShortestWay >= parent.right.rect.distanceTo(point)) {
            findNearest(parent.right, point);
        }
    }

    private static class Node {
        private final Point2D p;      // the point
        private final RectHV rect;    // the axis-aligned rectangle corresponding to this node
        private Node left;      // the left/bottom subtree
        private Node right;     // the right/top subtree

        private Node(Point2D p, RectHV rect) {
            this.p = p;
            this.rect = rect;
        }
    }

}
