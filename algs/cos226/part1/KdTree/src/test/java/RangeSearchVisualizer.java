/*************************************************************************
 *  Compilation:  javac RangeSearchVisualizer.java
 *  Execution:    java RangeSearchVisualizer input.txt
 *  Dependencies: PointSET.java KdTree.java Point2D.java RectHV.java
 *                StdDraw.java In.java
 * <p>
 *  Read points from a file (specified as a command-line argument) and
 *  draw to standard draw. Also draw all of the points in the rectangle
 *  the user selects by dragging the mouse.
 * <p>
 *  The range search results using the brute-force algorithm are drawn
 *  in red; the results using the kd-tree algorithms are drawn in blue.
 *
 *************************************************************************/
public class RangeSearchVisualizer {

    @SuppressWarnings("InfiniteLoopStatement")
    static void main(String[] args) {
        String filename = "circle100.txt";
        if (args != null && args.length > 0) {
            filename = args[0];
        }

        In in = ResourceFiles.open(RangeSearchVisualizer.class, filename);

        StdDraw.show(0);

        // initialize the data structures with N points from standard input
        // SpatialPointSet spsImpl = new PointSET();
        SpatialPointSet spsImpl = new KdTree();
        while (!in.isEmpty()) {
            double x = in.readDouble();
            double y = in.readDouble();
            Point2D p = new Point2D(x, y);
            spsImpl.insert(p);
        }

        double x0 = 0.0, y0 = 0.0;      // initial endpoint of rectangle
        double x1 = 0.0, y1 = 0.0;      // current location of mouse
        boolean isDragging = false;     // is the user dragging a rectangle

        // draw the points
        StdDraw.clear();
        StdDraw.setPenColor(StdDraw.BLACK);
        StdDraw.setPenRadius(.01);
        spsImpl.draw();

        while (true) {
            StdDraw.show(40);

            // user starts to drag a rectangle
            if (StdDraw.mousePressed() && !isDragging) {
                x0 = StdDraw.mouseX();
                y0 = StdDraw.mouseY();
                isDragging = true;
                continue;
            }
            // user is dragging a rectangle
            else if (StdDraw.mousePressed()) {
                x1 = StdDraw.mouseX();
                y1 = StdDraw.mouseY();
                continue;
            }
            // mouse no longer pressed
            else if (isDragging) {
                isDragging = false;
            }

            RectHV rect = new RectHV(Math.min(x0, x1), Math.min(y0, y1),
                                     Math.max(x0, x1), Math.max(y0, y1));
            // draw the points
            StdDraw.clear();
            StdDraw.setPenColor(StdDraw.BLACK);
            StdDraw.setPenRadius(.01);
            spsImpl.draw();

            // draw the rectangle
            StdDraw.setPenColor(StdDraw.BLACK);
            StdDraw.setPenRadius();
            rect.draw();

            // draw the range search results for brute-force data structure in red
            StdDraw.setPenRadius(.03);
            StdDraw.setPenColor(StdDraw.RED);

            // draw the range search results for kd-tree in blue
            StdDraw.setPenRadius(.02);
            StdDraw.setPenColor(StdDraw.BLUE);
            for (Point2D p : spsImpl.range(rect)) {
                p.draw();
            }

            StdDraw.show(40);
        }
    }
}
