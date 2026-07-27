import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * @author Lipatov Nikita
 */
public class Fast {

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
        In in = ResourceFiles.open(Fast.class, filename);
        int pointCount = in.readInt();
        Point[] points = new Point[pointCount];
        for (int index = 0; index < pointCount; index++) {
            int x = in.readInt();
            int y = in.readInt();
            Point point = new Point(x, y);
            point.draw();
            points[index] = point;
        }

        drawLines(points);

        StdDraw.show(0);
        StdDraw.setPenRadius();
    }

    static List<List<Point>> findSegments(Point[] points) {
        Objects.requireNonNull(points, "points");
        Point[] sortedPoints = points.clone();
        Arrays.stream(sortedPoints).forEach(point -> Objects.requireNonNull(point, "point"));
        Arrays.sort(sortedPoints);

        List<List<Point>> segments = new ArrayList<>();
        for (Point origin : sortedPoints) {
            Point[] candidates = Arrays.stream(sortedPoints)
                    .filter(point -> point != origin)
                    .toArray(Point[]::new);
            Arrays.sort(candidates, origin.SLOPE_ORDER);

            int start = 0;
            while (start < candidates.length) {
                double slope = origin.slopeTo(candidates[start]);
                int end = start + 1;
                while (end < candidates.length
                        && Double.compare(slope, origin.slopeTo(candidates[end])) == 0) {
                    end++;
                }

                if (end - start >= 3) {
                    List<Point> segment = new ArrayList<>(end - start + 1);
                    segment.add(origin);
                    segment.addAll(Arrays.asList(candidates).subList(start, end));
                    segment.sort(null);
                    if (segment.getFirst() == origin) {
                        segments.add(List.copyOf(segment));
                    }
                }
                start = end;
            }
        }
        return segments;
    }

    static void drawLines(Point[] points) {
        for (List<Point> segment : findSegments(points)) {
            segment.getFirst().drawTo(segment.getLast());
            StdOut.println(String.join(" -> ", segment.stream().map(Point::toString).toList()));
        }
    }
}
