import java.util.Comparator;

public class Solver {
    private final Comparator<SearchNode> manhattanAndHammingComparator = new ManhattanAndHammingComparator();

    private MinPQ<SearchNode> pq;
    private final MinPQ<SearchNode> pqTwin;

    private SearchNode goal = null;
    private boolean solvable = true;

    /**
     * find a solution to the initial board (using the A* algorithm)
     */
    public Solver(Board initial) {
        Comparator<SearchNode> manhattanComparator = new ManhattanComparator();
        pq = new MinPQ<>(manhattanComparator);
        Comparator<SearchNode> hammingComparator = new HammingComparator();
        pqTwin = new MinPQ<>(hammingComparator);

        SearchNode initNode = new SearchNode(null, initial, (short) 0);
        pq.insert(initNode);

        tryToSolve();
    }

    // package-private rather than private so the comparators and the nodes they order can be
    // unit tested directly; ManhattanAndHammingComparator is otherwise only reachable after a
    // solve has run for 20 seconds
    static class HammingComparator implements Comparator<SearchNode> {
        public int compare(SearchNode n1, SearchNode n2) {
            return n1.board.hamming()
                    - n2.board.hamming();
        }
    }

    static class ManhattanComparator implements Comparator<SearchNode> {
        public int compare(SearchNode n1, SearchNode n2) {
            return n1.board.manhattan()
                    + n1.moves
                    - n2.board.manhattan()
                    - n2.moves;
        }
    }

    static class ManhattanAndHammingComparator implements Comparator<SearchNode> {
        public int compare(SearchNode n1, SearchNode n2) {
            return n1.board.manhattan()
                    + n1.moves
                    - n2.board.manhattan()
                    - n2.moves
                    + n1.board.hamming()
                    - n2.board.hamming();
        }
    }

    // we cannot move prev and moves values to Board class
    record SearchNode(SearchNode prev, Board board, short moves) {
    }

    private void tryToSolve() {
        boolean isChangedComparator = false;
        long startTime = System.currentTimeMillis();
        while (true) {
            if (!isChangedComparator) {
                if (((System.currentTimeMillis() - startTime) / 1000) > 20) {
                    MinPQ<SearchNode> tempPq = new MinPQ<>(manhattanAndHammingComparator);
                    while (!pq.isEmpty()) {
                        tempPq.insert(pq.delMin());
                    }
                    pq = tempPq;
                    isChangedComparator = true;
                }
            }
            // solve pq
            SearchNode node = pq.delMin();
            if (node.board.isGoal()) {
                goal = node; // solved
                break;
            }

            for (Board board : node.board.neighbors()) {
                SearchNode n = new SearchNode(node, board, (short) (node.moves + 1));
                if (node.prev != null && board.equals(node.prev.board)) {
                    continue;
                }

                pq.insert(n);
            }

            // solve twin
            SearchNode twinNode = new SearchNode(null, node.board.twin(), (short) 0);
            pqTwin.insert(twinNode);

            twinNode = pqTwin.delMin();
            if (twinNode.board.isGoal()) {
                solvable = false; // impossible to solve
                break;
            }

            for (Board board : twinNode.board.neighbors()) {

                SearchNode n = new SearchNode(twinNode, board, (short) (twinNode.moves + 1));
                if (twinNode.prev != null && board.equals(twinNode.prev.board)) {
                    continue;
                }

                pqTwin.insert(n);
            }
        }
    }

    /**
     * @return is the initial board solvable?
     */
    public boolean isSolvable() {
        return solvable;
    }

    /**
     * @return min number of moves to solve initial board; -1 if no solution
     */
    public int moves() {
        if (!solvable) {
            return -1;
        }
        return goal.moves;
    }

    /**
     * @return sequence of boards in the shortest solution; null if no solution
     */
    public Iterable<Board> solution() {
        if (goal == null) {
            return null;
        }

        Stack<Board> stack = new Stack<>();
        SearchNode node = goal;
        stack.push(node.board);
        while (node.prev != null) {
            stack.push(node.prev.board);
            node = node.prev;
        }

        return stack;
    }

    /**
     * solve a slider puzzle
     */
    static void main(String[] args) {
        String pathname = "puzzle46.txt";
        if (args != null && args.length > 0) {
            pathname = args[0];
        }

        // create initial board from file
        In in = ResourceFiles.open(Solver.class, pathname);
        int N = in.readInt();
        int[][] blocks = new int[N][N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                blocks[i][j] = in.readInt();
            }
        }
        Board initial = new Board(blocks);

        // solve the puzzle
        long startDate = System.currentTimeMillis();
        Solver solver = new Solver(initial);
        long endDate = System.currentTimeMillis();
        System.out.println("Difference = " + (endDate - startDate));

        // print solution to standard output
        if (!solver.isSolvable()) {
            StdOut.println("No solution possible");
        } else {
            StdOut.println("Minimum number of moves = " + solver.moves());
            for (Board board : solver.solution()) {
                StdOut.println(board);
            }
        }
    }
}
