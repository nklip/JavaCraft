import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@code PercolationStats} command line client.
 *
 * <p>Its output can be captured, unlike the {@code main} methods elsewhere in these modules: it
 * writes with {@code System.out.println}, so redirecting {@code System.out} works. {@code StdOut}
 * would not be redirectable, because it caches {@code System.out} in its static initializer.
 *
 * <p>The experiments are random, so the assertions are on the shape of the answer rather than on
 * exact numbers - except for the estimate itself, which has to land near the known percolation
 * threshold of a large square lattice. That is the one property of this program worth pinning
 * down: an implementation that counted sites wrongly would still print three well-formed numbers.
 */
class PercolationStatsMainTest {

    /** The site percolation threshold for a large square lattice, roughly 0.593. */
    private static final double THRESHOLD = 0.5927;

    private static final Pattern MEAN = Pattern.compile("mean = (\\S+)");
    private static final Pattern STDDEV = Pattern.compile("stddev = (\\S+)");
    private static final Pattern INTERVAL =
            Pattern.compile("95% confidence interval = (\\S+), (\\S+)");

    @Test
    void testPrintsTheThreeStatisticsWhenGivenBothArguments() {
        String output = runMain(new String[]{"20", "30"});

        double mean = group(MEAN, output, 1);
        double stddev = group(STDDEV, output, 1);
        double low = group(INTERVAL, output, 1);
        double high = group(INTERVAL, output, 2);

        Assertions.assertTrue(mean > 0.0 && mean <= 1.0, () -> "mean out of range: " + mean);
        Assertions.assertTrue(stddev >= 0.0, () -> "negative stddev: " + stddev);
        Assertions.assertTrue(low <= mean, () -> low + " should not exceed the mean " + mean);
        Assertions.assertTrue(high >= mean, () -> high + " should not be below the mean " + mean);
    }

    /** One argument sets the grid size and leaves the repeat count at its default. */
    @Test
    void testAcceptsOnlyTheGridSize() {
        String output = runMain(new String[]{"8"});

        Assertions.assertTrue(output.contains("mean = "), output);
        Assertions.assertTrue(output.contains("stddev = "), output);
        Assertions.assertTrue(output.contains("95% confidence interval = "), output);
    }

    /**
     * No arguments means the built-in 200-by-200 grid over 200 experiments, which is large enough
     * for the estimate to settle near the known threshold. The tolerance is wide enough that a
     * random run will not trip it, and far tighter than a miscounting implementation could meet.
     */
    @Test
    void testTheDefaultRunEstimatesTheKnownPercolationThreshold() {
        double mean = group(MEAN, runMain(new String[0]), 1);

        Assertions.assertEquals(
                THRESHOLD, mean, 0.02,
                () -> "the 200-by-200 estimate " + mean + " is nowhere near the known threshold"
        );
    }

    /** The argument checks are null-safe, so a bare {@code main(null)} also uses the defaults. */
    @Test
    void testToleratesANullArgumentArray() {
        double mean = group(MEAN, runMain(null), 1);

        Assertions.assertEquals(THRESHOLD, mean, 0.02);
    }

    @Test
    void testRejectsArgumentsThatAreNotNumbers() {
        Assertions.assertThrows(
                NumberFormatException.class,
                () -> PercolationStats.main(new String[]{"not-a-number"})
        );
    }

    @Test
    void testRejectsNonPositiveArgumentsFromTheCommandLine() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PercolationStats.main(new String[]{"0", "10"})
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PercolationStats.main(new String[]{"10", "0"})
        );
    }

    /** Runs {@code main} with {@code System.out} redirected, restoring it whatever happens. */
    private static String runMain(String[] args) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            PercolationStats.main(args);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static double group(Pattern pattern, String output, int group) {
        Matcher matcher = pattern.matcher(output);
        Assertions.assertTrue(matcher.find(), () -> "no " + pattern.pattern() + " in:\n" + output);
        return Double.parseDouble(matcher.group(group));
    }
}
