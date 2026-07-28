import java.awt.Color;

/**
 * @author Lipatov Nikita
 * <p>
 * Complexity notation: {@code W} is the current picture width and {@code H} is its height.
 */
public class SeamCarver {

    private static final double BORDER_ENERGY = 195075.0;

    private Picture picture;
    private double [][]energy;
    private double [][]distTo;
    private int [][]edgeTo;

    /**
     * Required time complexity: {@code O(W * H)} in the worst case.
     * Actual time complexity: {@code O(W * H)}.
     */
    public SeamCarver(Picture picture) {
        if (picture == null) {
            throw new IllegalArgumentException("picture must not be null");
        }
        this.picture = picture;

        int pictureHeight = picture.height();
        int pictureWidth = picture.width();
        energy = new double[pictureHeight][pictureWidth];

        // 2d energy array using the energy()
        for (int row = 0; row < pictureHeight; row++) { // rows
            for (int col = 0; col < pictureWidth; col++) { // columns
                energy[row][col] = calculateEnergy(col, row, pictureWidth, pictureHeight);
            }
        }
    }

    /**
     * Required time complexity: {@code O(W * H)} or better in the worst case.
     * Actual time complexity: {@code O(1)}.
     */
    public Picture picture() {
        return picture;
    }

    /**
     * Required time complexity: {@code O(1)} in the worst case.
     * Actual time complexity: {@code O(1)}.
     */
    public int width() {
        return picture.width();
    }

    /**
     * Required time complexity: {@code O(1)} in the worst case.
     * Actual time complexity: {@code O(1)}.
     */
    public int height() {
        return picture.height();
    }

    /**
     * Required time complexity: {@code O(1)} in the worst case.
     * Actual time complexity: {@code O(1)}.
     */
    public double energy(int col, int row) {
        int pictureWidth = width();
        int pictureHeight = height();
        if (col < 0 || col >= pictureWidth || row < 0 || row >= pictureHeight) {
            throw new IndexOutOfBoundsException();
        }

        return calculateEnergy(col, row, pictureWidth, pictureHeight);
    }

    private double calculateEnergy(int col, int row, int pictureWidth, int pictureHeight) {
        if (col + 1 >= pictureWidth || col == 0 || row + 1 >= pictureHeight || row == 0) {
            return BORDER_ENERGY;
        }

        return calculateX(col, row) + calculateY(col, row);
    }

    private double calculateX(int col, int row) {
        Color colorRight = picture.get(col + 1, row);
        Color colorLeft  = picture.get(col - 1, row);

        return Math.pow(Math.abs(colorRight.getRed()   - colorLeft.getRed()),   2.0)
             + Math.pow(Math.abs(colorRight.getBlue()  - colorLeft.getBlue()),  2.0)
             + Math.pow(Math.abs(colorRight.getGreen() - colorLeft.getGreen()), 2.0);

    }

    private double calculateY(int col, int row) {
        Color colorDown = picture.get(col, row + 1);
        Color colorUp   = picture.get(col, row - 1);

        return Math.pow(Math.abs(colorDown.getRed()   - colorUp.getRed()),   2.0)
             + Math.pow(Math.abs(colorDown.getBlue()  - colorUp.getBlue()),  2.0)
             + Math.pow(Math.abs(colorDown.getGreen() - colorUp.getGreen()), 2.0);
    }

    /**
     * Required time complexity: {@code O(W * H)} in the worst case.
     * Actual time complexity: {@code O(W * H)}.
     */
    public int[] findVerticalSeam() {
        int []seam = new int[height()];
        if (height() == 1 || width() == 1) {
            return seam;
        }

        // distTo and edgeTo should calculate each time
        distTo = new double[height()][width()];
        edgeTo = new int[height()][width()];

        // init distTo
        for (int row = 0; row < height(); row++) { // rows
            for (int col = 0; col < width(); col++) { // columns
                distTo[row][col] = -1;
            }
        }

        // init distTo and edgeTo for first row
        for (int col = 0; col < width(); col++) { // columns
            distTo[0][col] = energy[0][col];
            edgeTo[0][col] = -1; // init value
        }

        // calculate best paths
        for (int row = 0; row < height() - 1; row++) { // rows
            for (int col = 0; col < width(); col++) { // columns
                verticalRelax(col, row);
            }
        }

        // find the best cell in the final row
        int bestX = 0;
        double best = distTo[height() - 1][bestX];
        for (int col = 1; col < width(); col++) {
            if (distTo[height() - 1][col] < best) {
                best = distTo[height() - 1][col];
                bestX = col;
            }
        }

        // build seam by following predecessors
        seam[height() - 1] = bestX;
        for (int row = height() - 1; row > 0; row--) {
            bestX += edgeTo[row][bestX];
            seam[row - 1] = bestX;
        }

        return seam;
    }

    // column x and row y
    private void verticalRelax(int col, int row) {
        if (col == 0) {
            verticalEvaluate(col, row, col, row + 1);
            verticalEvaluate(col, row, col + 1, row + 1);
        } else if (col + 1 == width()) {
            verticalEvaluate(col, row, col - 1, row + 1);
            verticalEvaluate(col, row, col, row + 1);
        } else {
            verticalEvaluate(col, row, col - 1, row + 1);
            verticalEvaluate(col, row, col, row + 1);
            verticalEvaluate(col, row, col + 1, row + 1);
        }
    }

    // column x and row y
    private void verticalEvaluate(int parentX, int parentY, int col, int row) {

        double temp = distTo[parentY][parentX] + energy[row][col];
        if (distTo[row][col] == -1 || temp < distTo[row][col]) {
            distTo[row][col] = temp;
            if (col < parentX) {
                edgeTo[row][col] =  1;
            } else if (col == parentX) {
                edgeTo[row][col] =  0;
            } else {
                edgeTo[row][col] = -1;
            }
        }
    }

    /**
     * Required time complexity: {@code O(W * H)} in the worst case.
     * Actual time complexity: {@code O(W * H)}.
     */
    public int[] findHorizontalSeam() {
        int []seam = new int[width()];
        if (width() == 1 || height() == 1) {
            return seam;
        }

        // distTo and edgeTo should calculate each time
        distTo = new double[height()][width()];
        edgeTo = new int[height()][width()];

        // init distTo
        for (int row = 0; row < height(); row++) { // rows
            for (int col = 0; col < width(); col++) { // columns
                distTo[row][col] = -1;
            }
        }

        // init distTo and edgeTo for first column
        for (int row = 0; row < height(); row++) { // rows
            distTo[row][0] = energy[row][0];
            edgeTo[row][0] = -1; // init value
        }

        // calculate best paths
        for (int col = 0; col < width() - 1; col++) { // columns
            for (int row = 0; row < height(); row++) { // rows
                horizontalRelax(col, row);
            }
        }

        // find the best cell in the final column
        int bestY = 0;
        double best = distTo[bestY][width() - 1];
        for (int row = 1; row < height(); row++) { // rows
            if (distTo[row][width() - 1] < best) {
                best = distTo[row][width() - 1];
                bestY = row;
            }
        }

        // build seam by following predecessors
        seam[width() - 1] = bestY;
        for (int col = width() - 1; col > 0; col--) {
            bestY += edgeTo[bestY][col];
            seam[col - 1] = bestY;
        }

        return seam;
    }

    // column x and row y
    private void horizontalRelax(int x, int y) {
        if (y == 0) {
            horizontalEvaluate(x, y, x + 1, y);
            horizontalEvaluate(x, y, x + 1, y + 1);
        } else if (y + 1 == height()) {
            horizontalEvaluate(x, y, x + 1, y - 1);
            horizontalEvaluate(x, y, x + 1, y);
        } else {
            horizontalEvaluate(x, y, x + 1, y - 1);
            horizontalEvaluate(x, y, x + 1, y);
            horizontalEvaluate(x, y, x + 1, y + 1);
        }
    }

    // column x and row y
    private void horizontalEvaluate(int parentX, int parentY, int x, int y) {

        double temp = distTo[parentY][parentX] + energy[y][x];
        if (distTo[y][x] == -1 || temp < distTo[y][x]) {
            distTo[y][x] = temp;
            if (parentY < y) {
                edgeTo[y][x] = -1;
            } else if (y == parentY) {
                edgeTo[y][x] = 0;
            } else {
                edgeTo[y][x] = 1;
            }
        }
    }

    /**
     * Required time complexity: {@code O(W * H)} in the worst case.
     * Actual time complexity: {@code O(W * H)}.
     */
    public void removeVerticalSeam(int[] seam) {
        if (seam == null) {
            throw new NullPointerException("seam must not be null");
        }
        if (height() <= 1) {
            throw new IllegalArgumentException();
        }
        if (width() <= 1) {
            throw new IllegalArgumentException();
        }
        if (seam.length != height()) {
            throw new IllegalArgumentException();
        }

        Picture result = new Picture(width() - 1, height());
        for (int row = 0; row < height(); row++) {
            int removedColumn = seam[row];

            if (removedColumn < 0 || removedColumn >= width()) {
                throw new IllegalArgumentException();
            }

            if (row > 0 && Math.abs(removedColumn - seam[row - 1]) > 1) {
                throw new IllegalArgumentException();
            }

            for (int col = 0; col < width() - 1; col++) {
                if (col < removedColumn) {
                    result.set(col, row, picture.get(col, row));
                } else {
                    result.set(col, row, picture.get(col + 1, row));
                }
            }
        }

        picture = result;

        // possible improvement - avoid recomputing the parts of the energy matrix that don't change.
        energy = new double[height()][width()];
        // 2d energy array using the energy()
        for (int row = 0; row < height(); row++) { // rows
            for (int col = 0; col < width(); col++) { // columns
                energy[row][col] = energy(col, row);
            }
        }
    }

    /**
     * Required time complexity: {@code O(W * H)} in the worst case.
     * Actual time complexity: {@code O(W * H)}.
     */
    public void removeHorizontalSeam(int[] seam) {
        if (seam == null) {
            throw new NullPointerException("seam must not be null");
        }
        if (height() <= 1) {
            throw new IllegalArgumentException();
        }
        if (width() <= 1) {
            throw new IllegalArgumentException();
        }
        if (seam.length != width()) {
            throw new IllegalArgumentException();
        }

        Picture result = new Picture(width(), height() - 1);
        for (int col = 0; col < width(); col++) {
            int removedRow = seam[col];

            if (removedRow < 0 || removedRow >= height()) {
                throw new IllegalArgumentException();
            }

            if (col > 0 && Math.abs(removedRow - seam[col - 1]) > 1) {
                throw new IllegalArgumentException();
            }

            for (int row = 0; row < height() - 1; row++) {
                if (row < removedRow) {
                    result.set(col, row, picture.get(col, row));
                } else {
                    result.set(col, row, picture.get(col, row + 1));
                }
            }
        }

        picture = result;

        // possible improvement - avoid recomputing the parts of the energy matrix that don't change.
        energy = new double[height()][width()];
        // 2d energy array using the energy()
        for (int row = 0; row < height(); row++) { // rows
            for (int col = 0; col < width(); col++) { // columns
                energy[row][col] = energy(col, row);
            }
        }

    }

}
