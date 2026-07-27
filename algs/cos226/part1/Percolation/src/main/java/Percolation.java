/**
 * @author Lipatov Nikita
 */
public class Percolation {

    private static final boolean BLOCK = false;
    private static final boolean OPEN  = true;

    private final int size;
    private final int virUpperCell;
    private final int virBottomCell;
    private final boolean[] grid;
    private final WeightedQuickUnionUF quickUnionUF;
    private final WeightedQuickUnionUF quickPercolate;
    private boolean isPercolated;

    // create N-by-N grid, with all sites blocked
    public Percolation(int N) {
        if (N <= 0) {
            throw new IllegalArgumentException("Grid has initial size is 0 or negative value!");
        }
        size = N;
        grid = new boolean[size * size + 2];

        virUpperCell = size * size;
        virBottomCell = size * size + 1;
        grid[virUpperCell]  = OPEN; // virtual top
        grid[virBottomCell] = OPEN; // virtual bottom

        for (int i = 0; i < size * size; i++) { // row
            grid[i] = BLOCK;
        }
        quickUnionUF = new WeightedQuickUnionUF(size * size + 2);
        quickPercolate = new WeightedQuickUnionUF(size * size + 2);
    }

    private int convertToCell(int i, int j) {
        return size * (i-1) + j-1;
    }

    private void validateValues(int i, int j) {
        if (isOutOfBorderCase(i, j)) {
            if (i > size || i <= 0) {
                throw new IndexOutOfBoundsException("Column index i out of bounds");
            } else {
                throw new IndexOutOfBoundsException("Column index j out of bounds");
            }
        }
    }

    // is site (row i, column j) open?
    public boolean isOpen(int i, int j) {
        validateValues(i, j);
        return (grid[convertToCell(i, j)] == OPEN);
    }

    // is site (row i, column j) full?
    public boolean isFull(int i, int j) {
        validateValues(i, j);

        int p = convertToCell(i, j);

        if (grid[p] == BLOCK) {
            return false;
        }

        return quickUnionUF.connected(virUpperCell, p);
    }

    public boolean percolates() {
        if (!isPercolated) {
            isPercolated = quickPercolate.connected(virUpperCell, virBottomCell);
        }
        return isPercolated;
    }

    // open site (row i, column j) if it is not open already
    public void open(int i, int j)  {
        validateValues(i, j);
        int p = convertToCell(i, j);

        if (grid[p] == OPEN) {
            return;
        }

        grid[p] = OPEN;

        if (i == 1) {
            quickUnionUF.union(p, virUpperCell);
            quickPercolate.union(p, virUpperCell);
        } else {
            union(p, i - 1, j); // upper
        }
        if (i == size) {
            quickPercolate.union(p, virBottomCell);
        }

        union(p, i + 1, j); // bottom
        union(p, i, j - 1); // left
        union(p, i, j + 1); // right
    }

    private void union(int p, int vi, int vj) {
        if (isNotBlockOrBorder(vi, vj)) {
            int q = convertToCell(vi, vj);

            if (!quickUnionUF.connected(p, q)) {
                quickUnionUF.union(p, q);
                quickPercolate.union(p, q);
            }
        }
    }

    private boolean isNotBlockOrBorder(int i, int j) {
        if (isOutOfBorderCase(i, j)) { // border cases
            return false;
        }
        return (grid[convertToCell(i, j)] != BLOCK);
    }

    private boolean isOutOfBorderCase(int i, int j) {
        // border cases
        return i <= 0 || j <= 0 || i > size || j > size;
    }

}
