import java.util.Arrays;

/**
 * @author Lipatov Nikita
 */
public class Board {

    private final short []tiles;
    private final short []goal;
    private final short N; // double size
    private final short D; // one size

    // construct a board from an N-by-N array of tiles
    public Board(int[][] blocks) {
        if (blocks.length < 2 || blocks.length >= 128) {
            throw new IllegalArgumentException("N is too small or big!");
        }
        N = (short) blocks.length;
        D = (short) (N * N);
        tiles = new short[D];
        goal  = new short[D];

        // initial 1D array
        for (short i = 0; i < N; i++) {
            for (short y = 0; y < N; y++) {
                tiles[convertToCell(i, y)] = (short) blocks[i][y];
            }
        }

        // goal 1D array
        for (short i = 1; i <= D; i++) {
            if (i == D) {
                this.goal[i - 1] = 0;
            } else {
                this.goal[i - 1] = i;
            }
        }
    }

    private Board(Board board) {
        this.N = board.N;
        this.D = board.D;

        this.tiles = new short[board.tiles.length];
        this.goal  = new short[board.goal.length];
        for (int i = 0; i < board.tiles.length; i++) {
            this.tiles[i] = board.tiles[i];
            this.goal[i]  = board.goal[i];
        }
    }

    private short convertToCell(int i, int j) {
        return (short) (N * i + j);
    }

    // (where tiles[i][j] = block in row i, column j)
    // board dimension N
    public int dimension() {
        return N;
    }

    /*
        Hamming priority function.
        The number of tiles in the wrong position, plus the number of moves made so far to get to the search node.
        Intuitively, a search node with a small number of tiles in the wrong position is close to the goal,
        and we prefer a search node that have been reached using a small number of moves.
    */
    public int hamming() {
        int hamming = 0;
        for (short i = 0; i < D; i++) {
            if (goal[i] != 0 && tiles[i] != goal[i]) {
                hamming++;
            }
        }
        return hamming;
    }

    /*
        Manhattan priority function.
        The sum of the Manhattan distances (sum of the vertical and horizontal distance) from the tiles to their
        goal positions, plus the number of moves made so far to get to the search node.
    */
    public int manhattan() {
        int manhattan = 0;
        for (short i = 0; i < D; i++) {
            if (tiles[i] != 0 && tiles[i] != goal[i]) {
                manhattan += movesRequired(i);
            }
        }
        return manhattan;
    }

    private int movesRequired(int i) {
        int curRow = (i / N) + 1;
        int curColumn = i - (N * (curRow - 1)) + 1;

        int value = tiles[i];

        int goalRow = ((value - 1) / N) + 1;
        int goalColumn = value - (N * (goalRow - 1));
        return Math.abs(goalRow - curRow) + Math.abs(goalColumn - curColumn);
    }

    // is this board the goal board?
    public boolean isGoal() {
        for (short i = 0; i < D; i++) {
            if (this.tiles[i] != this.goal[i]) {
                return false;
            }
        }
        return true;
    }

    // does this board equal y?
    @Override
    public boolean equals(Object y) {
        if (y == this) {
            return true;
        }
        if (y == null) {
            return false;
        }
        if (y.getClass() != this.getClass()) {
            return false;
        }

        Board that = (Board) y;
        // D is always N * N and both are final,
        // so comparing N alone settles the dimension
        if (this.N != that.N) {
            return false;
        }
        for (short i = 0; i < D; i++) {
            if (this.tiles[i] != that.tiles[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(tiles);
    }

    // string representation of this board (in the output format specified below)
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(N).append("\n");
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                s.append(String.format("%2d ", tiles[convertToCell(i, j)]));
            }
            s.append("\n");
        }
        return s.toString();
    }

    // a board that is obtained by exchanging two adjacent tiles in the same row
    public Board twin() {
        Board that = new Board(this);

        int rRow = 0;
        int rCol = 0;
        while (that.tiles[convertToCell(rRow, rCol)] == 0 || that.tiles[convertToCell(rRow, (short) (rCol + 1))] == 0) {
            if (rCol + 2 == that.N) { // 2 = (rCol = 0 is 1) + (1 is future cell)
                rRow++;
                rCol = 0;
            } else {
                rCol++;
            }
        }
        short temp = that.tiles[convertToCell(rRow, (short) (rCol + 1))];
        that.tiles[convertToCell(rRow, (short) (rCol + 1))] = that.tiles[convertToCell(rRow, rCol)];
        that.tiles[convertToCell(rRow, rCol)] = temp;

        return that;
    }

    // all neighboring boards
    public Iterable<Board> neighbors() {
        Queue<Board> possibleMoves = new Queue<>();
        for (short i = 0; i < N; i++) {
            for (short y = 0; y < N; y++) {
                if (this.tiles[convertToCell(i, y)] == 0) {
                    short temp;
                    if (i != 0) {     // upper case
                        Board upMove = new Board(this);
                        temp = upMove.tiles[convertToCell(i - 1, y)];
                        upMove.tiles[convertToCell(i - 1, y)] = upMove.tiles[convertToCell(i, y)];
                        upMove.tiles[convertToCell(i, y)] = temp;
                        possibleMoves.enqueue(upMove);
                    }
                    if (y != 0)  {     // left case
                        Board upMove = new Board(this);
                        temp = upMove.tiles[convertToCell(i, y - 1)];
                        upMove.tiles[convertToCell(i, y - 1)] = upMove.tiles[convertToCell(i, y)];
                        upMove.tiles[convertToCell(i, y)] = temp;
                        possibleMoves.enqueue(upMove);
                    }
                    if (i != N - 1) { // bottom case
                        Board upMove = new Board(this);
                        temp = upMove.tiles[convertToCell(i + 1, y)];
                        upMove.tiles[convertToCell(i + 1, y)] = upMove.tiles[convertToCell(i, y)];
                        upMove.tiles[convertToCell(i, y)] = temp;
                        possibleMoves.enqueue(upMove);
                    }
                    if (y != N - 1)  { // right case
                        Board upMove = new Board(this);
                        temp = upMove.tiles[convertToCell(i, y + 1)];
                        upMove.tiles[convertToCell(i, y + 1)] = upMove.tiles[convertToCell(i, y)];
                        upMove.tiles[convertToCell(i, y)] = temp;
                        possibleMoves.enqueue(upMove);
                    }
                    break;
                }
            }
        }
        return possibleMoves;
    }

}
