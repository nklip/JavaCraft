import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Shortest ancestral path (SAP)
 * First attempt.
 * @author Lipatov Nikita
 */
public class SAPFirst {
    private final Digraph digraph;
    private int cachedV;
    private int cachedW;
    private SAPCache cachedQuery;

    // constructor takes a digraph (not necessarily a DAG) (directed acyclic graph)
    public SAPFirst(Digraph g) {
        if (g == null) {
            throw new IllegalArgumentException("Digraph must not be null");
        }
        digraph = new Digraph(g);
    }

    // length of shortest ancestral path between v and w; -1 if no such path
    public int length(int v, int w) {
        return shortestAncestralPath(v, w).length;
    }

    // a common ancestor of v and w that participates in the shortest ancestral path; -1 if no such path
    public int ancestor(int v, int w) {
        return shortestAncestralPath(v, w).ancestor;
    }

    /**
     * @return the smallest total any still-undiscovered common ancestor could have
     *
     * <p>A vertex that has not been found yet is missing from at least one side, so it is at least
     * one step beyond that side's frontier - and could be sitting on the other side's start
     * vertex, contributing nothing there. That makes the bound one more than the shallower
     * <em>active</em> frontier, not the sum of the two: a vertex reached cheaply from one side may
     * still be reached late from the other, and stopping on the sum would miss it.
     */
    private static int lowerBoundOnWhatIsLeft(
            Deque<Integer> frontierV, Deque<Integer> frontierW, int levelV, int levelW) {
        if (frontierV.isEmpty()) {
            return levelW + 1;
        }
        if (frontierW.isEmpty()) {
            return levelV + 1;
        }
        return Math.min(levelV, levelW) + 1;
    }

    /**
     * Searches from both ends at once, one level at a time, and stops as soon as no unexplored
     * vertex could beat what has already been found.
     *
     * <p>This is what makes the class worth keeping alongside {@code SAP}, which runs two complete
     * breadth-first searches over the whole digraph for every query. Here each side only grows
     * until nothing undiscovered could even match the best total found so far. The search runs one
     * level past the point where it could still improve, so that every vertex achieving the best
     * total has been seen and the tie between them can be settled the same way {@code SAP} settles
     * it. On a large hierarchy most queries stop after a few levels.
     *
     * <p>Stopping at the <em>first</em> vertex reached from both sides would be wrong, which is
     * the trap the earlier version fell into: the first meeting is not necessarily the cheapest
     * one, so the search has to keep going until the bound above is reached. Each side also tracks
     * its own visited vertices - sharing one set makes a vertex reached twice from the same side
     * look like a meeting point, and leaves nothing to stop a walk going round a cycle forever.
     */
    private SAPCache shortestAncestralPath(int v, int w) {
        validateVertex(v);
        validateVertex(w);
        if (cachedQuery != null
                && ((cachedV == v && cachedW == w) || (cachedV == w && cachedW == v))) {
            return cachedQuery;
        }

        int[] fromV = new int[digraph.V()];
        int[] fromW = new int[digraph.V()];
        Arrays.fill(fromV, -1);
        Arrays.fill(fromW, -1);

        Deque<Integer> frontierV = new ArrayDeque<>();
        Deque<Integer> frontierW = new ArrayDeque<>();
        fromV[v] = 0;
        frontierV.add(v);
        fromW[w] = 0;
        frontierW.add(w);

        int shortest = Integer.MAX_VALUE;
        int ancestor = -1;
        if (v == w) {
            shortest = 0;
            ancestor = v;
        }

        int levelV = 0;
        int levelW = 0;
        while (!frontierV.isEmpty() || !frontierW.isEmpty()) {
            if (lowerBoundOnWhatIsLeft(frontierV, frontierW, levelV, levelW) > shortest) {
                break; // nothing undiscovered can match, let alone beat, what is already found
            }

            boolean expandV = !frontierV.isEmpty() && (frontierW.isEmpty() || levelV <= levelW);
            int[] near = expandV ? fromV : fromW;
            int[] far = expandV ? fromW : fromV;
            Deque<Integer> frontier = expandV ? frontierV : frontierW;
            int level = expandV ? levelV : levelW;

            Deque<Integer> next = new ArrayDeque<>();
            for (int vertex : frontier) {
                for (int adjacent : digraph.adj(vertex)) {
                    if (near[adjacent] >= 0) {
                        continue; // already reached from this side
                    }
                    near[adjacent] = level + 1;
                    next.add(adjacent);
                    if (far[adjacent] < 0) {
                        continue;
                    }
                    int total = near[adjacent] + far[adjacent];
                    // ties are broken on the lowest vertex, which is what SAP settles on when it
                    // scans every vertex in order, so the two agree on the exact ancestor
                    if (total < shortest || (total == shortest && adjacent < ancestor)) {
                        shortest = total;
                        ancestor = adjacent;
                    }
                }
            }

            if (expandV) {
                frontierV = next;
                levelV++;
            } else {
                frontierW = next;
                levelW++;
            }
        }

        cachedV = v;
        cachedW = w;
        cachedQuery = new SAPCache(ancestor, ancestor == -1 ? -1 : shortest);
        return cachedQuery;
    }

    // length of shortest ancestral path between any vertex in v and any vertex in w; -1 if no such path
    public int length(Iterable<Integer> v, Iterable<Integer> w) {
        return shortestAncestralPath(v, w)[0];
    }

    // a common ancestor that participates in the shortest ancestral path; -1 if no such path
    public int ancestor(Iterable<Integer> v, Iterable<Integer> w) {
        return shortestAncestralPath(v, w)[1];
    }

    /**
     * Runs one breadth-first search from each set of source vertices and finds their shortest
     * common ancestor.
     *
     * @return {@code {length, ancestor}}, both -1 when the source sets have no common ancestor
     */
    private int[] shortestAncestralPath(Iterable<Integer> v, Iterable<Integer> w) {
        List<Integer> verticesV = validatedVertices(v);
        List<Integer> verticesW = validatedVertices(w);
        BreadthFirstDirectedPaths fromV = new BreadthFirstDirectedPaths(digraph, verticesV);
        BreadthFirstDirectedPaths fromW = new BreadthFirstDirectedPaths(digraph, verticesW);
        int shortestLength = -1;
        int shortestAncestor = -1;
        for (int vertex = 0; vertex < digraph.V(); vertex++) {
            if (!fromV.hasPathTo(vertex) || !fromW.hasPathTo(vertex)) {
                continue;
            }

            int length = fromV.distTo(vertex) + fromW.distTo(vertex);
            if (shortestLength < 0 || length < shortestLength) {
                shortestLength = length;
                shortestAncestor = vertex;
            }
        }
        return new int[]{shortestLength, shortestAncestor};
    }

    private List<Integer> validatedVertices(Iterable<Integer> vertices) {
        if (vertices == null) {
            throw new IllegalArgumentException("Vertices must not be null");
        }

        List<Integer> validated = new ArrayList<>();
        for (Integer vertex : vertices) {
            if (vertex == null) {
                throw new IllegalArgumentException("Vertex must not be null");
            }
            validateVertex(vertex);
            validated.add(vertex);
        }
        return validated;
    }

    private void validateVertex(int vertex) {
        if (vertex < 0 || vertex >= digraph.V()) {
            throw new IllegalArgumentException("Vertex is outside the prescribed range: " + vertex);
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

    // do unit testing of this class
    static void main(String[] args) {
        String path = "digraph1.txt";
        if (args != null && args.length > 0) {
            path = args[0];
        }
        In in = ResourceFiles.open(SAPFirst.class, path);
        Digraph G = new Digraph(in);
        SAPFirst sap = new SAPFirst(G);
        while (!StdIn.isEmpty()) {
            int v = StdIn.readInt();
            int w = StdIn.readInt();
            int length   = sap.length(v, w);
            int ancestor = sap.ancestor(v, w);
            StdOut.printf("length = %d, ancestor = %d\n", length, ancestor);
        }
    }
}
