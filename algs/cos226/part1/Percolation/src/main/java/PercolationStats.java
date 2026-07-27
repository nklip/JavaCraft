/**
 * @author Lipatov Nikita
 */
public class PercolationStats {

    private final double[] u;

    // perform T independent experiments on an N-by-N grid
    public PercolationStats(int N, int T) {
        if (N <= 0) {
            throw new IllegalArgumentException("Grid has initial size is 0 or negative value!");
        }
        if (T <= 0) {
            throw new IllegalArgumentException("Times have initial size is 0 or negative value!");
        }

        u = new double[T];
        for (int times = 0; times < T; times++) {
            Percolation percolation = new Percolation(N);
            int loops;
            for (loops = 0; loops < N*N;) {
                int i = StdRandom.uniform(N) + 1;
                int j = StdRandom.uniform(N) + 1;
                if (!percolation.isOpen(i, j)) {
                    percolation.open(i, j);
                    loops++;
                }
                if (percolation.percolates()) {
                    break;
                }
            }
            u[times] = (double) loops/(N*N);
        }
    }

    // sample mean of percolation threshold
    public double mean() {
        return StdStats.mean(u);
    }

    // sample standard deviation of percolation threshold
    public double stddev() {
        return StdStats.stddev(u);
    }

    // low  endpoint of 95% confidence interval
    public double confidenceLo() {
        return mean() - (1.96*stddev()/Math.sqrt(u.length));
    }

    // high endpoint of 95% confidence interval
    public double confidenceHi() {
        return mean() + (1.96*stddev()/Math.sqrt(u.length));
    }

    // test client (described below)
    static void main(String[] args) {
        int T = 200; // default value
        int N = 200; // default value

        if (args != null && args.length >= 1) {
            N = Integer.parseInt(args[0]);
        }
        if (args != null && args.length >= 2) {
            T = Integer.parseInt(args[1]);
        }

        PercolationStats stats = new PercolationStats(N, T);
        System.out.println("mean = " + stats.mean());
        System.out.println("stddev = " + stats.stddev());
        System.out.println("95% confidence interval = " + stats.confidenceLo() + ", " + stats.confidenceHi());
    }

}
