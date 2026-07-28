import java.util.ArrayList;
import java.util.List;

/**
 * Shortest ancestral path (SAP)
 * <p>
 * Complexity notation:
 * {@code V} and {@code E} are the digraph's vertex and edge counts,
 * {@code S} is the total number of vertices supplied by two iterable arguments, and
 * {@code Q} is the number of queries processed by the test client.
 *
 * @author Lipatov Nikita
 */
public class SAP {
    private final Digraph digraph;
    private int cachedV;
    private int cachedW;
    private SAPCache cachedQuery;

    /**
     * Required time complexity: {@code O(V + E)} in the worst case.
     * Actual time complexity: {@code O(V + E)}.
     */
    public SAP(Digraph g) {
        if (g == null) {
            throw new NullPointerException("Digraph must not be null");
        }
        digraph = new Digraph(g);
    }

    /**
     * Required time complexity: {@code O(V + E)} in the worst case.
     * Actual worst-case time complexity: {@code O(V + E)}; a repeated or reversed cached query
     * takes {@code O(1)}.
     */
    public int length(int v, int w) {
        return getSapCache(v, w).length;
    }

    /**
     * Required time complexity: {@code O(V + E)} in the worst case.
     * Actual worst-case time complexity: {@code O(V + E)}; a repeated or reversed cached query
     * takes {@code O(1)}.
     */
    public int ancestor(int v, int w) {
        return getSapCache(v, w).ancestor;
    }

    /**
     * Required time complexity: {@code O(V + E)} in the worst case.
     * Actual time complexity: {@code O(V + E + S)}.
     */
    public int length(Iterable<Integer> v, Iterable<Integer> w) {
        SAPCache cache = getSapCache(v, w);
        if (cache == null) {
            return -1;
        }
        return cache.length;
    }

    /**
     * Required time complexity: {@code O(V + E)} in the worst case.
     * Actual time complexity: {@code O(V + E + S)}.
     */
    public int ancestor(Iterable<Integer> v, Iterable<Integer> w) {
        SAPCache cache = getSapCache(v, w);
        if (cache == null) {
            return -1;
        }
        return cache.ancestor;
    }

    /**
     * Runs one breadth-first search from each set of source vertices and returns their shortest
     * common ancestor.
     *
     * @return the shortest ancestral path, or {@code null} when the source sets have no common
     *         ancestor
     */
    private SAPCache getSapCache(Iterable<Integer> v, Iterable<Integer> w) {
        if (v == null || w == null) {
            throw new NullPointerException("Vertices must not be null");
        }
        List<Integer> verticesV = validatedVertices(v);
        List<Integer> verticesW = validatedVertices(w);
        BreadthFirstDirectedPaths fromV = new BreadthFirstDirectedPaths(digraph, verticesV);
        BreadthFirstDirectedPaths fromW = new BreadthFirstDirectedPaths(digraph, verticesW);
        SAPCache shortest = null;
        for (int vertex = 0; vertex < digraph.V(); vertex++) {
            if (!fromV.hasPathTo(vertex) || !fromW.hasPathTo(vertex)) {
                continue;
            }

            int length = fromV.distTo(vertex) + fromW.distTo(vertex);
            if (shortest == null || length < shortest.length) {
                shortest = new SAPCache(vertex, length);
            }
        }
        return shortest;
    }

    private SAPCache getSapCache(int v, int w) {
        validateVertex(v);
        validateVertex(w);
        if (cachedQuery != null
                && ((cachedV == v && cachedW == w) || (cachedV == w && cachedW == v))) {
            return cachedQuery;
        }

        BreadthFirstDirectedPaths bfdV = new BreadthFirstDirectedPaths(digraph, v);
        BreadthFirstDirectedPaths bfdW = new BreadthFirstDirectedPaths(digraph, w);

        int shortestLength = -1;
        int ancestor = -1;
        for (int i = 0; i < digraph.V(); i++) {
            if (!bfdV.hasPathTo(i) || !bfdW.hasPathTo(i)) {
                continue;
            }

            int length = bfdV.distTo(i) + bfdW.distTo(i);
            if (shortestLength == -1 || length < shortestLength) {
                shortestLength = length;
                ancestor = i;
            }
        }

        cachedV = v;
        cachedW = w;
        cachedQuery = new SAPCache(ancestor, shortestLength);
        return cachedQuery;
    }

    private List<Integer> validatedVertices(Iterable<Integer> vertices) {
        if (vertices == null) {
            throw new NullPointerException("Vertices must not be null");
        }

        List<Integer> validated = new ArrayList<>();
        for (Integer vertex : vertices) {
            if (vertex == null) {
                throw new NullPointerException("Vertex must not be null");
            }
            validateVertex(vertex);
            validated.add(vertex);
        }
        return validated;
    }

    private void validateVertex(int vertex) {
        if (vertex < 0 || vertex >= digraph.V()) {
            throw new IndexOutOfBoundsException("Vertex is outside the prescribed range: " + vertex);
        }
    }

    private static final class SAPCache {
        private final int ancestor;
        private final int length;

        private SAPCache(int ancestor, int length) {
            this.ancestor = ancestor;
            this.length = length;
        }
    }

    /**
     * Required time complexity: not specified; this is the assignment test client.
     * Actual time complexity: {@code O((Q + 1) * (V + E))}.
     */
    static void main(String[] args) {
        String path = "digraph1.txt";
        if (args != null && args.length > 0) {
            path = args[0];
        }
        In in = ResourceFiles.open(SAP.class, path);
        Digraph G = new Digraph(in);
        SAP sap = new SAP(G);
        while (!StdIn.isEmpty()) {
            int v = StdIn.readInt();
            int w = StdIn.readInt();
            int length   = sap.length(v, w);
            int ancestor = sap.ancestor(v, w);
            StdOut.printf("length = %d, ancestor = %d\n", length, ancestor);
        }
    }
}
