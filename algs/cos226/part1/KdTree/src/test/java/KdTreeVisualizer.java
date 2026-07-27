/*************************************************************************
 *  Compilation:  javac KdTreeVisualizer.java
 *  Execution:    java KdTreeVisualizer
 *  Dependencies: StdDraw.java Point2D.java KdTree.java
 * <p>
 *  Add the points that the user clicks in the standard draw window
 *  to a kd-tree and draw the resulting kd-tree.
 *
 *************************************************************************/

@SuppressWarnings({"RedundantSuppression", "ExplicitToImplicitClassMigration"})
public class KdTreeVisualizer {

    @SuppressWarnings({"InfiniteLoopStatement", "unused"})
    static void main(String[] args) {
        RectHV rect = new RectHV(0.0, 0.0, 1.0, 1.0);
        StdDraw.show(0);
        //SpatialPointSet spsImpl = new PointSET();
        SpatialPointSet spsImpl = new KdTree();
        while (true) {
            if (StdDraw.mousePressed()) {
                double x = StdDraw.mouseX();
                double y = StdDraw.mouseY();
                System.out.printf("%8.6f %8.6f\n", x, y);
                Point2D p = new Point2D(x, y);
                if (rect.contains(p)) {
                    StdOut.printf("%8.6f %8.6f\n", x, y);
                    spsImpl.insert(p);
                    StdDraw.clear();
                    spsImpl.draw();
                }
            }
            StdDraw.show(50);
        }
    }
}
