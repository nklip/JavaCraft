/*************************************************************************
 *  Compilation:  javac NearestNeighborVisualizer.java
 *  Execution:    java NearestNeighborVisualizer input.txt
 *  Dependencies: PointSET.java KdTree.java Point2D.java In.java StdDraw.java
 * <p>
 *  Read points from a file (specified as a command-line argument) and
 *  draw to standard draw. Highlight the closest point to the mouse.
 * <p>
 *  The nearest neighbor according to the brute-force algorithm is drawn
 *  in red; the nearest neighbor using the kd-tree algorithm is drawn in blue.
 *
 *************************************************************************/
public class NearestNeighborVisualizer {

    @SuppressWarnings("InfiniteLoopStatement")
    static void main(String[] args) {
        String filename = "circle10.txt";
        if (args != null && args.length > 0) {
            filename = args[0];
        }

        In in = ResourceFiles.open(NearestNeighborVisualizer.class, filename);

        StdDraw.show(0);

        // initialize the two data structures with point from standard input
        //SpatialPointSet spsImpl = new PointSET();
        SpatialPointSet spsImpl = new KdTree();
        while (!in.isEmpty()) {
            double x = in.readDouble();
            double y = in.readDouble();
            Point2D p = new Point2D(x, y);
            spsImpl.insert(p);
        }

        while (true) {

            // the location (x, y) of the mouse
            //double x = 0.024472; //StdDraw.mouseX();
            double x = StdDraw.mouseX();
            //double y = 0.654508; //StdDraw.mouseY();
            double y = StdDraw.mouseY();
            //if(StdDraw.mousePressed()) {

                Point2D query = new Point2D(x, y);

                // draw all the points
                StdDraw.clear();
                StdDraw.setPenColor(StdDraw.BLACK);
                StdDraw.setPenRadius(.01);
                spsImpl.draw();

                // draw in red the nearest neighbor (using brute-force algorithm)
                StdDraw.setPenRadius(.03);
                StdDraw.setPenColor(StdDraw.RED);
                StdDraw.setPenRadius(.02);

                // draw in blue the nearest neighbor (using kd-tree algorithm)
                StdDraw.setPenColor(StdDraw.BLUE);
                spsImpl.nearest(query).draw();
                StdDraw.show(0);
                StdDraw.show(40);
                //break;
            //}
        }
    }
}
