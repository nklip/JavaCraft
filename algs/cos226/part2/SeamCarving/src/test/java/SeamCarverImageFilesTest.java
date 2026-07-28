import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drives {@link SeamCarver} from the images in {@code src/test/resources}.
 *
 * <p>Every {@code NxM.png} ships with an {@code NxM.printseams.txt} beside it, holding the exact
 * output {@code PrintSeams} produces for that image: the energy of every pixel, the horizontal and
 * vertical seams marked in brackets, and the total energy of each. Comparing against that is an
 * end-to-end check of energy, both seam searches and the printing, all at once - and it is a
 * transcript someone recorded from a working implementation, not something derived here.
 *
 * <p>Alongside it two oracles are computed in this file. The first is the dual-gradient energy
 * written out directly; note it is the squared gradient with no square root, which is what the
 * {@code 195075} border constant implies, that being {@code 3 * 255 * 255}. The second is a plain
 * dynamic program over the energy matrix giving the minimum total energy of any seam. That second
 * one matters most: a seam can be perfectly valid - contiguous, in range, right length - and still
 * not be the cheapest, and only comparing totals catches that.
 *
 * <p>{@code SeamCarver} implements the horizontal and vertical searches as two separate bodies of
 * code, so they are also checked against each other: transposing an image has to turn its vertical
 * seam into the horizontal seam of the transpose, and leave every energy in place. That holds
 * exactly on all of these images, with no ties to blur it.
 *
 * <p>{@code HJoceanTransposed.png} is not the transpose of {@code HJocean.png} despite the name -
 * every one of its pixels differs - so it is treated as an unrelated image.
 */
class SeamCarverImageFilesTest {

    /** Energy of any pixel on the border: {@code 3 * 255 * 255}. */
    private static final double BORDER_ENERGY = 195075.0;

    static Stream<String> images() {
        return Stream.of("3x7.png", "4x6.png", "5x6.png", "6x5.png", "7x3.png",
                "10x12.png", "12x10.png", "HJocean.png", "HJoceanTransposed.png");
    }

    /** The hand-sized images, for the checks that are quadratic or that transpose a copy. */
    static Stream<String> smallImages() {
        return Stream.of("3x7.png", "4x6.png", "5x6.png", "6x5.png", "7x3.png",
                "10x12.png", "12x10.png");
    }

    // ---------- the shipped transcripts ----------

    /**
     * The whole of {@code PrintSeams} against the recorded transcript, byte for byte. The files end
     * with one more newline than the program emits, so both sides are trimmed at the end only.
     */
    @ParameterizedTest(name = "PrintSeams over {0} matches the shipped transcript")
    @MethodSource("images")
    void testPrintSeamsMatchesTheShippedTranscript(String image) {
        String expected = readText(image.replace(".png", ".printseams.txt"));

        String actual = captureStandardOut(() -> PrintSeams.main(new String[]{image}));

        Assertions.assertEquals(expected.stripTrailing(), actual.stripTrailing(), image);
    }

    /** The transcript opens by stating the size, which has to be the size of the image. */
    @ParameterizedTest(name = "{0}: the transcript header states the real dimensions")
    @MethodSource("images")
    void testTheTranscriptHeaderMatchesTheImageDimensions(String image) {
        Picture picture = picture(image);
        SeamCarver carver = new SeamCarver(picture);

        String header = readText(image.replace(".png", ".printseams.txt")).lines().findFirst().orElseThrow();

        Assertions.assertEquals(
                "image is " + picture.width() + " columns by " + picture.height() + " rows",
                header, image);
        Assertions.assertEquals(picture.width(), carver.width(), image);
        Assertions.assertEquals(picture.height(), carver.height(), image);
    }

    // ---------- energy ----------

    @ParameterizedTest(name = "{0}: every energy matches the dual-gradient formula")
    @MethodSource("images")
    void testEnergyMatchesTheDualGradientFormula(String image) {
        Picture picture = picture(image);
        SeamCarver carver = new SeamCarver(picture);

        for (int x = 0; x < carver.width(); x++) {
            for (int y = 0; y < carver.height(); y++) {
                int column = x;
                int row = y;
                Assertions.assertEquals(
                        dualGradientEnergy(picture, x, y), carver.energy(x, y), 1e-9,
                        () -> image + ": energy(" + column + ", " + row + ")");
            }
        }
    }

    @ParameterizedTest(name = "{0}: the border carries the border energy")
    @MethodSource("images")
    void testBorderPixelsCarryTheBorderEnergy(String image) {
        SeamCarver carver = new SeamCarver(picture(image));

        for (int x = 0; x < carver.width(); x++) {
            Assertions.assertEquals(BORDER_ENERGY, carver.energy(x, 0), image);
            Assertions.assertEquals(BORDER_ENERGY, carver.energy(x, carver.height() - 1), image);
        }
        for (int y = 0; y < carver.height(); y++) {
            Assertions.assertEquals(BORDER_ENERGY, carver.energy(0, y), image);
            Assertions.assertEquals(BORDER_ENERGY, carver.energy(carver.width() - 1, y), image);
        }
    }

    // ---------- seams ----------

    @ParameterizedTest(name = "{0}: both seams are contiguous and inside the image")
    @MethodSource("images")
    void testBothSeamsAreContiguousAndInsideTheImage(String image) {
        SeamCarver carver = new SeamCarver(picture(image));

        assertIsASeam(carver.findVerticalSeam(), carver.height(), carver.width(), image + " vertical");
        assertIsASeam(carver.findHorizontalSeam(), carver.width(), carver.height(), image + " horizontal");
    }

    /**
     * The check that a valid seam is not enough: its total energy has to equal the minimum any seam
     * could have, which the dynamic program below computes without going near {@code SeamCarver}.
     */
    @ParameterizedTest(name = "{0}: the vertical seam is the cheapest one")
    @MethodSource("images")
    void testTheVerticalSeamHasTheMinimumPossibleEnergy(String image) {
        SeamCarver carver = new SeamCarver(picture(image));
        double[][] energy = energyMatrix(carver);

        int[] seam = carver.findVerticalSeam();
        double total = 0;
        for (int row = 0; row < carver.height(); row++) {
            total += carver.energy(seam[row], row);
        }

        Assertions.assertEquals(cheapestSeamEnergy(energy), total, 1e-6, image);
    }

    /** The same for the horizontal search, over the transposed energy matrix. */
    @ParameterizedTest(name = "{0}: the horizontal seam is the cheapest one")
    @MethodSource("images")
    void testTheHorizontalSeamHasTheMinimumPossibleEnergy(String image) {
        SeamCarver carver = new SeamCarver(picture(image));
        double[][] energy = transposed(energyMatrix(carver));

        int[] seam = carver.findHorizontalSeam();
        double total = 0;
        for (int column = 0; column < carver.width(); column++) {
            total += carver.energy(column, seam[column]);
        }

        Assertions.assertEquals(cheapestSeamEnergy(energy), total, 1e-6, image);
    }

    // ---------- the two searches against each other ----------

    /**
     * Transposing an image moves every pixel to the mirrored position, so every energy has to move
     * with it. This is the cheap half of the cross-check below.
     */
    @ParameterizedTest(name = "{0}: energy survives transposition")
    @MethodSource("smallImages")
    void testEnergyIsUnchangedByTransposingTheImage(String image) {
        Picture picture = picture(image);
        SeamCarver original = new SeamCarver(picture);
        SeamCarver flipped = new SeamCarver(transpose(picture));

        for (int x = 0; x < original.width(); x++) {
            for (int y = 0; y < original.height(); y++) {
                int column = x;
                int row = y;
                Assertions.assertEquals(
                        original.energy(x, y), flipped.energy(y, x), 1e-9,
                        () -> image + ": energy(" + column + ", " + row + ") after transposing");
            }
        }
    }

    /**
     * The two searches are written out separately in {@code SeamCarver}, so this is the test that
     * holds them to the same answer: the cheapest vertical seam of an image is the cheapest
     * horizontal seam of its transpose. If one search drifts from the other, this fails even though
     * both still return perfectly valid seams.
     */
    @ParameterizedTest(name = "{0}: the vertical seam is the transpose's horizontal seam")
    @MethodSource("smallImages")
    void testTheVerticalSearchAgreesWithTheHorizontalSearchOnATranspose(String image) {
        Picture picture = picture(image);

        int[] vertical = new SeamCarver(picture).findVerticalSeam();
        int[] horizontalOfTranspose = new SeamCarver(transpose(picture)).findHorizontalSeam();

        Assertions.assertArrayEquals(vertical, horizontalOfTranspose, image);
    }

    // ---------- removal ----------

    /**
     * Carving five vertical seams narrows the image by five and leaves the height alone, and what
     * is left has to stay a coherent image: every energy still matches the formula, and the next
     * seam is still the cheapest one.
     */
    @ParameterizedTest(name = "{0}: carving seams narrows the image and keeps it consistent")
    @MethodSource("smallImages")
    void testCarvingVerticalSeamsNarrowsTheImage(String image) {
        SeamCarver carver = new SeamCarver(picture(image));
        int originalWidth = carver.width();
        int originalHeight = carver.height();
        int toRemove = Math.min(3, originalWidth - 2);

        for (int removed = 0; removed < toRemove; removed++) {
            carver.removeVerticalSeam(carver.findVerticalSeam());
        }

        Assertions.assertEquals(originalWidth - toRemove, carver.width(), image);
        Assertions.assertEquals(originalHeight, carver.height(), image);

        Picture carved = carver.picture();
        Assertions.assertEquals(carver.width(), carved.width(), image);
        Assertions.assertEquals(carver.height(), carved.height(), image);
        for (int x = 0; x < carver.width(); x++) {
            for (int y = 0; y < carver.height(); y++) {
                Assertions.assertEquals(
                        dualGradientEnergy(carved, x, y), carver.energy(x, y), 1e-9,
                        image + ": energy is stale after carving");
            }
        }
        Assertions.assertEquals(
                cheapestSeamEnergy(energyMatrix(carver)),
                totalEnergyOf(carver, carver.findVerticalSeam()), 1e-6,
                image + ": the seam after carving is no longer minimal");
    }

    /**
     * Which pixels survive, not just how many. The test above compares the carved image against
     * its own energies, so it holds however the columns are shifted; this one names the expected
     * result - every row is the original row with exactly the seam pixel deleted and everything to
     * its right moved one place left.
     */
    @ParameterizedTest(name = "{0}: carving keeps exactly the pixels either side of the seam")
    @MethodSource("smallImages")
    void testCarvingAVerticalSeamDeletesExactlyTheSeamPixels(String image) {
        Picture original = picture(image);
        SeamCarver carver = new SeamCarver(original);
        int[] seam = carver.findVerticalSeam();

        carver.removeVerticalSeam(seam);
        Picture carved = carver.picture();

        Assertions.assertEquals(original.width() - 1, carved.width(), image);
        for (int row = 0; row < original.height(); row++) {
            for (int column = 0; column < carved.width(); column++) {
                int sourceColumn = column < seam[row] ? column : column + 1;
                int expectedRow = row;
                int expectedColumn = column;
                Assertions.assertEquals(
                        original.get(sourceColumn, row), carved.get(column, row),
                        () -> image + ": (" + expectedColumn + ", " + expectedRow + ") should come from column "
                                + (expectedColumn < seam[expectedRow] ? expectedColumn : expectedColumn + 1));
            }
        }
    }

    /** The same for a horizontal seam, where the rows below the seam move up one. */
    @ParameterizedTest(name = "{0}: carving a horizontal seam deletes exactly the seam pixels")
    @MethodSource("smallImages")
    void testCarvingAHorizontalSeamDeletesExactlyTheSeamPixels(String image) {
        Picture original = picture(image);
        SeamCarver carver = new SeamCarver(original);
        int[] seam = carver.findHorizontalSeam();

        carver.removeHorizontalSeam(seam);
        Picture carved = carver.picture();

        Assertions.assertEquals(original.height() - 1, carved.height(), image);
        for (int column = 0; column < original.width(); column++) {
            for (int row = 0; row < carved.height(); row++) {
                int sourceRow = row < seam[column] ? row : row + 1;
                int expectedRow = row;
                int expectedColumn = column;
                Assertions.assertEquals(
                        original.get(column, sourceRow), carved.get(column, row),
                        () -> image + ": (" + expectedColumn + ", " + expectedRow + ") came from the wrong row");
            }
        }
    }

    @ParameterizedTest(name = "{0}: carving a horizontal seam shortens the image")
    @MethodSource("smallImages")
    void testCarvingAHorizontalSeamShortensTheImage(String image) {
        SeamCarver carver = new SeamCarver(picture(image));
        int originalWidth = carver.width();
        int originalHeight = carver.height();

        carver.removeHorizontalSeam(carver.findHorizontalSeam());

        Assertions.assertEquals(originalWidth, carver.width(), image);
        Assertions.assertEquals(originalHeight - 1, carver.height(), image);
    }

    // ---------- helpers ----------

    private static void assertIsASeam(int[] seam, int expectedLength, int bound, String what) {
        Assertions.assertEquals(expectedLength, seam.length, what + ": wrong length");
        for (int index = 0; index < seam.length; index++) {
            int position = index;
            Assertions.assertTrue(
                    seam[index] >= 0 && seam[index] < bound,
                    () -> what + ": " + seam[position] + " is outside 0.." + (bound - 1));
            if (index > 0) {
                Assertions.assertTrue(
                        Math.abs(seam[index] - seam[index - 1]) <= 1,
                        () -> what + ": jumps from " + seam[position - 1] + " to " + seam[position]);
            }
        }
    }

    /** The dual-gradient energy, squared - no square root, matching the 195075 border constant. */
    private static double dualGradientEnergy(Picture picture, int x, int y) {
        if (x == 0 || y == 0 || x == picture.width() - 1 || y == picture.height() - 1) {
            return BORDER_ENERGY;
        }
        return squaredDifference(picture.get(x - 1, y), picture.get(x + 1, y))
                + squaredDifference(picture.get(x, y - 1), picture.get(x, y + 1));
    }

    private static double squaredDifference(Color left, Color right) {
        double red = left.getRed() - right.getRed();
        double green = left.getGreen() - right.getGreen();
        double blue = left.getBlue() - right.getBlue();
        return red * red + green * green + blue * blue;
    }

    private static double[][] energyMatrix(SeamCarver carver) {
        double[][] energy = new double[carver.height()][carver.width()];
        for (int row = 0; row < carver.height(); row++) {
            for (int column = 0; column < carver.width(); column++) {
                energy[row][column] = carver.energy(column, row);
            }
        }
        return energy;
    }

    private static double totalEnergyOf(SeamCarver carver, int[] verticalSeam) {
        double total = 0;
        for (int row = 0; row < verticalSeam.length; row++) {
            total += carver.energy(verticalSeam[row], row);
        }
        return total;
    }

    /**
     * @return the smallest total energy of any top-to-bottom seam, by a row-at-a-time dynamic
     *         program over the energy matrix - no seam is ever reconstructed, only its cost
     */
    private static double cheapestSeamEnergy(double[][] energy) {
        int height = energy.length;
        int width = energy[0].length;
        double[] previous = energy[0].clone();
        double[] current = new double[width];

        for (int row = 1; row < height; row++) {
            for (int column = 0; column < width; column++) {
                double best = previous[column];
                if (column > 0) {
                    best = Math.min(best, previous[column - 1]);
                }
                if (column < width - 1) {
                    best = Math.min(best, previous[column + 1]);
                }
                current[column] = energy[row][column] + best;
            }
            double[] swap = previous;
            previous = current;
            current = swap;
        }

        double cheapest = Double.MAX_VALUE;
        for (double total : previous) {
            cheapest = Math.min(cheapest, total);
        }
        return cheapest;
    }

    private static double[][] transposed(double[][] matrix) {
        double[][] result = new double[matrix[0].length][matrix.length];
        for (int row = 0; row < matrix.length; row++) {
            for (int column = 0; column < matrix[0].length; column++) {
                result[column][row] = matrix[row][column];
            }
        }
        return result;
    }

    private static Picture transpose(Picture picture) {
        Picture result = new Picture(picture.height(), picture.width());
        for (int x = 0; x < picture.width(); x++) {
            for (int y = 0; y < picture.height(); y++) {
                result.set(y, x, picture.get(x, y));
            }
        }
        return result;
    }

    /** Loads a fixture image through the support module, so no working directory is assumed. */
    private static Picture picture(String image) {
        return new Picture(ResourceFiles.resolve(SeamCarver.class, image).toFile());
    }

    /** {@code PrintSeams} writes with {@code System.out}, so its output can be captured. */
    private static String captureStandardOut(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.US_ASCII));
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.US_ASCII);
    }

    private static String readText(String fixture) {
        Path directory = ResourceFiles.fixtureDirectory(SeamCarver.class);
        Assertions.assertNotNull(directory, "fixture directory should be found from the code location");
        try {
            return Files.readString(directory.resolve(fixture), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + fixture, e);
        }
    }
}
