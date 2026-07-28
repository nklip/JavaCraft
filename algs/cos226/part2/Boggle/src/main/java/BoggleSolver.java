import java.util.Set;
import java.util.TreeSet;

/**
 * @author Lipatov Nikita
 * <p>
 * Complexity notation:
 * {@code C} is the total number of dictionary characters,
 * {@code L} is a word length,
 * {@code B} is the number of board cells,
 * {@code P} is the number of simple board paths explored, and
 * {@code W} is the number of words found. {@code P} is exponential in
 * {@code B} in the worst case.
 */
@SuppressWarnings({"RedundantSuppression", "ExplicitToImplicitClassMigration"})
public class BoggleSolver {
    private final TST<Integer> tst;

    /**
     * Required time complexity: not specified; the throughput test constructs one solver and
     * reuses it.
     * Actual time complexity: {@code O(C)} for the fixed 26-letter alphabet.
     */
    public BoggleSolver(String[] dictionary) {
        tst = new TST<>();
        for (int i = 0; i < dictionary.length; i++) {
            tst.put(dictionary[i], i);
        }
    }

    /**
     * Required time complexity: no asymptotic bound is specified; for full credit the assignment
     * requires thousands of random Hasbro boards per second.
     * Actual worst-case time complexity: {@code O(B^2 + P * (C + B + log W))}, because each
     * {@code TST.keysWithPrefix()} call may enumerate the matching dictionary subtree.
     */
    public Iterable<String> getAllValidWords(BoggleBoard board) {

        Set<String> words = new TreeSet<>();
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                boolean[][] visited = new boolean[board.rows()][board.cols()];
                deepFirstSearch(board, row, col, words, visited, "");
            }
        }
        return words;
    }

    // deep first search
    private void deepFirstSearch(BoggleBoard board, int row, int col, Set<String> words, boolean[][] visited, String prefix) {
        if (visited[row][col]) {
            return;
        }

        char letter = board.getLetter(row, col);
        prefix = prefix + ((letter == 'Q') ? "QU" : letter);

        if (prefix.length() > 2 && tst.contains(prefix)) {
            words.add(prefix);
        }

        Iterable<String> prefixes = tst.keysWithPrefix(prefix);
        boolean isPrefixExist = false;
        for (String _ : prefixes) {
            isPrefixExist = true;
            break;
        }
        if (!isPrefixExist) {
            return;
        }

        visited[row][col] = true;

        // do a DFS for all adjacent cells
        if (row > 0) {
            deepFirstSearch(board, row - 1, col, words, visited, prefix);
            if (col > 0) {
                deepFirstSearch(board, row - 1, col - 1, words, visited, prefix);
            }
            if (col < board.cols() - 1) {
                deepFirstSearch(board, row - 1, col + 1, words, visited, prefix);
            }
        }
        if (row < board.rows() - 1) {
            if (col > 0) {
                deepFirstSearch(board, row + 1, col - 1, words, visited, prefix);
            }
            if (col < board.cols() - 1) {
                deepFirstSearch(board, row + 1, col + 1, words, visited, prefix);
            }
            deepFirstSearch(board, row + 1, col, words, visited, prefix);
        }
        if (col > 0) {
            deepFirstSearch(board, row, col - 1, words, visited, prefix);
        }
        if (col < board.cols() - 1) {
            deepFirstSearch(board, row, col + 1, words, visited, prefix);
        }

        visited[row][col] = false;
    }

    /**
     * Required time complexity: not specified by the assignment.
     * Actual time complexity: {@code O(L)} for the fixed 26-letter alphabet.
     *
     * <pre>
     * word length    points
     * 0-2            0
     * 3-4            1
     * 5              2
     * 6              3
     * 7              5
     * 8+             11
     * </pre>
     */
    public int scoreOf(String word) {
        if (word == null) {
            throw new IllegalArgumentException("Word is null!");
        }
        int length = word.length();
        if (length < 3) {
            return 0;
        }
        if (tst.contains(word)) {
            if (length == 3 || length == 4) {
                return 1;
            } else if (length == 5) {
                return 2;
            } else if (length == 6) {
                return 3;
            } else if (length == 7) {
                return 5;
            } else { // 8++
                return 11;
            }
        } else {
            return 0;
        }
    }

    /**
     % java BoggleSolver dictionary-algs4.txt board4x4.txt     % java BoggleSolver dictionary-algs4.txt board-q.txt
     AID                                                       EQUATION
     DIE                                                       EQUATIONS
     END                                                       ...
     ENDS                                                      QUERIES
     ...                                                       QUESTION
     YOU                                                       QUESTIONS
     Score = 33                                                ...
                                                               TRIES
                                                               Score = 84
     */
    static void main(String[] args) {
        String dicInputFile = "dictionary-algs4.txt";
        //String gameInputFile = "board4x4.txt";
        String gameInputFile = "board-q.txt";

        if (args != null && args.length > 1) {
            dicInputFile = args[0];
            gameInputFile = args[1];
        }

        In in = ResourceFiles.open(BoggleSolver.class, dicInputFile);
        String []dictionary = in.readAllStrings();

        BoggleSolver solver = new BoggleSolver(dictionary);
        BoggleBoard board = new BoggleBoard(gameInputFile);
        int score = 0;
        for (String word : solver.getAllValidWords(board))
        {
            StdOut.println(word);
            score += solver.scoreOf(word);
        }
        StdOut.println("Score = " + score);
    }
}
