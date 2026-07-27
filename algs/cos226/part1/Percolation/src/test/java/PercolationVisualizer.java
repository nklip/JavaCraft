/*
 *  Compilation:  javac PercolationVisualizer.java
 *  Execution:    java PercolationVisualizer input.txt
 *  Dependencies: Percolation.java StdDraw.java In.java
 *
 *  This program takes the name of a file as a command-line argument.
 *  From that file, it
 *
 *    - Reads the grid size N of the percolation system.
 *    - Creates an N-by-N grid of sites (initially all blocked)
 *    - Reads in a sequence of sites (row i, column j) to open.
 *
 *  After each site is opened, it draws full sites in light blue,
 *  open sites (that aren't full) in white, and blocked sites in black,
 *  with site (1, 1) in the upper left-hand corner.
 *
 ****************************************************************************/
import java.awt.Font;

public class PercolationVisualizer {

    // delay in milliseconds (controls animation speed)
    private static final int DELAY = 0;

    // draw N-by-N percolation system
    public static void draw(Percolation perc, int N) {
        StdDraw.clear();
        StdDraw.setPenColor(StdDraw.BLACK);
        StdDraw.setXscale(-3, N+3);
        StdDraw.setYscale(-3, N+3);
        StdDraw.filledSquare(N/2.0, N/2.0, N/2.0);

        // draw N-by-N grid
        int opened = 0;
        for (int row = 1; row <= N; row++) {
            for (int col = 1; col <= N; col++) {
                if (perc.isFull(row, col)) {
                    StdDraw.setPenColor(StdDraw.BOOK_LIGHT_BLUE);
                    opened++;
                } else if (perc.isOpen(row, col)) {
                    StdDraw.setPenColor(StdDraw.WHITE);
                    opened++;
                } else {
                    StdDraw.setPenColor(StdDraw.BLACK);
                }
                StdDraw.filledSquare(col - 0.5, N - row + 0.5, 0.45);
            }
        }

        // write status text
        StdDraw.setFont(new Font("SansSerif", Font.PLAIN, 12));
        StdDraw.setPenColor(StdDraw.BLACK);
        StdDraw.text(.25*N, -N*.025, opened + " open sites");
        if (perc.percolates()) {
            StdDraw.text(.75*N, -N*.025, "percolates");
        } else {
            StdDraw.text(.75*N, -N*.025, "does not percolate");
        }

    }

    static void main(String[] args) {
        String filename = "greeting57.txt";
        if (args != null && args.length > 0) {
            filename = args[0];
        }
        In in = ResourceFiles.open(PercolationVisualizer.class, filename); // input file
        int N = in.readInt(); // N-by-N percolation system

        // turn on animation mode
        StdDraw.show(0);

        // repeatedly read in sites to open and draw resulting system
        Percolation percolation = new Percolation(N);
        draw(percolation, N); // just black square
        StdDraw.show(DELAY);
        while (!in.isEmpty()) {
            int i = in.readInt();
            int j = in.readInt();
            percolation.open(i, j);
            draw(percolation, N);
            StdDraw.show(DELAY);
        }
        StdDraw.show(6000);
    }

}
