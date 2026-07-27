import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @author Lipatov Nikita
 */
public class Brute {

    static void main(String[] args) {
        String filename = "rs1423.txt";
        if (args != null && args.length >= 1) {
            filename = args[0];
        }

        StdDraw.setXscale(0, 32768);
        StdDraw.setYscale(0, 32768);
        StdDraw.show(0);
        StdDraw.setPenRadius(0.01); // make the points a bit larger

        // read in the input
        In in = ResourceFiles.open(Brute.class, filename);
        int N = in.readInt();
        ArrayList<Point> points = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            int x = in.readInt();
            int y = in.readInt();
            Point p = new Point(x, y);
            p.draw();
            points.add(p);
        }

        Collections.sort(points);

        drawLines(points);

        // display to screen all at once
        StdDraw.show(0);

        // reset the pen radius
        StdDraw.setPenRadius();
    }

    static List<List<Point>> findSegments(List<Point> points) {
        Objects.requireNonNull(points, "points");
        List<Point> sortedPoints = new ArrayList<>(points);
        sortedPoints.forEach(point -> Objects.requireNonNull(point, "point"));
        Collections.sort(sortedPoints);

        List<List<Point>> segments = new ArrayList<>();
        int size = sortedPoints.size();
        for (int i = 0; i < size - 3; i++) {
            for (int y = i + 1; y < size - 2; y++) {
                double slope = sortedPoints.get(i).slopeTo(sortedPoints.get(y));
                for (int m = y + 1; m < size - 1; m++) {
                    if (Double.compare(slope, sortedPoints.get(i).slopeTo(sortedPoints.get(m))) == 0) {
                        for (int n = m + 1; n < size; n++) {
                            if (Double.compare(slope, sortedPoints.get(i).slopeTo(sortedPoints.get(n))) == 0) {
                                segments.add(List.of(
                                        sortedPoints.get(i),
                                        sortedPoints.get(y),
                                        sortedPoints.get(m),
                                        sortedPoints.get(n)
                                ));
                            }
                        }
                    }
                }
            }
        }
        return segments;
    }

    private static void drawLines(List<Point> points) {
        for (List<Point> segment : findSegments(points)) {
            segment.getFirst().drawTo(segment.getLast());
            StdOut.println(String.join(" -> ", segment.stream().map(Point::toString).toList()));
        }
    }
}
