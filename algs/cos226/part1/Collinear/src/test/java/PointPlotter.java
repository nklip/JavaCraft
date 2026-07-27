/*************************************************************************
 *  Compilation:  javac PointPlotter.java
 *  Execution:    java PointPlotter input.txt
 *  Dependencies: Point.java, In.java, StdDraw.java
 * <p>
 *  Takes the name of a file as a command-line argument.
 *  Reads in an integer N followed by N pairs of points (x, y)
 *  with coordinates between 0 and 32,767, and plots them using
 *  standard drawing.
 *
 *************************************************************************/
public class PointPlotter {

    static void main(String[] args) {
        //int border = 1000;
        // rescale coordinates and turn on animation mode
        //StdDraw.setXscale(0 - border, 32768 + border);
        //StdDraw.setYscale(0 - border, 32768 + border);
        StdDraw.setXscale(0, 32768);
        StdDraw.setYscale(0, 32768);
        StdDraw.show(0);
        StdDraw.setPenRadius(0.01);  // make the points a bit larger

        // read in the input
        String filename = "rs1423.txt";
        if (args != null && args.length >= 1) {
            filename = args[0];
        }
        In in = ResourceFiles.open(PointPlotter.class, filename);
        int N = in.readInt();

        Point[] points = new Point[N];
        for (int i = 0; i < N; i++) {
            int x = in.readInt();
            int y = in.readInt();
            Point p = new Point(x, y);
            p.draw();
            points[i] = p;
        }

        Fast.drawLines(points);

        // display to screen all at once
        StdDraw.show(0);

        // reset the pen radius
        StdDraw.setPenRadius();
    }
}
