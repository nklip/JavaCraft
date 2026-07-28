import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Color;

class SeamCarverTest {

    @Test
    void testReportsDimensionsAndPixelEnergy() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new SeamCarver(null));

        Picture picture = solidPicture(3, 3, Color.BLUE);
        SeamCarver seamCarver = new SeamCarver(picture);

        Assertions.assertEquals(3, seamCarver.width());
        Assertions.assertEquals(3, seamCarver.height());
        Assertions.assertEquals(195075.0, seamCarver.energy(0, 0));
        Assertions.assertEquals(0.0, seamCarver.energy(1, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> seamCarver.energy(-1, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> seamCarver.energy(3, 0));
    }

    @Test
    void testFindsValidHorizontalAndVerticalSeams() {
        SeamCarver seamCarver = new SeamCarver(solidPicture(4, 3, Color.BLACK));

        int[] vertical = seamCarver.findVerticalSeam();
        int[] horizontal = seamCarver.findHorizontalSeam();

        assertValidSeam(vertical, seamCarver.height(), seamCarver.width());
        assertValidSeam(horizontal, seamCarver.width(), seamCarver.height());
    }

    @Test
    void testHorizontalSeamStaysInBoundsWhenTopRowsTie() {
        Picture picture = solidPicture(6, 3, Color.BLACK);
        for (int col = 0; col < picture.width(); col++) {
            picture.set(col, 2, Color.WHITE);
        }
        SeamCarver seamCarver = new SeamCarver(picture);

        assertValidSeam(seamCarver.findHorizontalSeam(), seamCarver.width(), seamCarver.height());
    }

    @Test
    void testVerticalSeamStaysInBoundsWhenLeftColumnsTie() {
        Picture picture = solidPicture(4, 4, Color.BLACK);
        for (int col = 0; col < 3; col++) {
            picture.set(col, 1, Color.WHITE);
        }
        SeamCarver seamCarver = new SeamCarver(picture);

        assertValidSeam(seamCarver.findVerticalSeam(), seamCarver.height(), seamCarver.width());
    }

    @Test
    void testFindsMinimumEnergySeamsFromAssignmentSample() {
        SeamCarver seamCarver = new SeamCarver(picture("3x7.png"));

        Assertions.assertArrayEquals(new int[]{0, 1, 1, 1, 1, 1, 0}, seamCarver.findVerticalSeam());
        Assertions.assertArrayEquals(new int[]{1, 2, 1}, seamCarver.findHorizontalSeam());
    }

    /**
     * The wide counterpart of the sample above: seven columns by three rows, so the seams swap
     * roles. The horizontal one now has to cross seven columns and dips into the single interior
     * row, while the vertical one is three long and can only step between neighbouring columns.
     *
     * <p>The expected values and totals are read off {@code 7x3.printseams.txt}, which marks each
     * seam and prints its energy. Only the top and bottom rows are border here, so the interior
     * energies dominate the horizontal total: 195075 at each end plus the five interior pixels.
     */
    @Test
    void testFindsMinimumEnergySeamsInAWideImage() {
        SeamCarver seamCarver = new SeamCarver(picture("7x3.png"));

        Assertions.assertEquals(7, seamCarver.width());
        Assertions.assertEquals(3, seamCarver.height());

        int[] horizontal = seamCarver.findHorizontalSeam();
        int[] vertical = seamCarver.findVerticalSeam();

        Assertions.assertArrayEquals(new int[]{0, 1, 1, 1, 1, 1, 0}, horizontal);
        Assertions.assertArrayEquals(new int[]{2, 3, 2}, vertical);

        double horizontalEnergy = 0;
        for (int column = 0; column < horizontal.length; column++) {
            horizontalEnergy += seamCarver.energy(column, horizontal[column]);
        }
        double verticalEnergy = 0;
        for (int row = 0; row < vertical.length; row++) {
            verticalEnergy += seamCarver.energy(vertical[row], row);
        }

        Assertions.assertEquals(722403.0, horizontalEnergy);
        Assertions.assertEquals(438090.0, verticalEnergy);
    }

    @Test
    void testFindsSeamsForSingleRowOrColumn() {
        SeamCarver oneRow = new SeamCarver(solidPicture(4, 1, Color.BLACK));
        SeamCarver oneColumn = new SeamCarver(solidPicture(1, 4, Color.BLACK));

        Assertions.assertArrayEquals(new int[]{0}, oneRow.findVerticalSeam());
        Assertions.assertArrayEquals(new int[]{0}, oneColumn.findHorizontalSeam());
        Assertions.assertArrayEquals(new int[]{0, 0, 0, 0}, oneRow.findHorizontalSeam());
        Assertions.assertArrayEquals(new int[]{0, 0, 0, 0}, oneColumn.findVerticalSeam());
    }

    @Test
    void testRemovesValidSeamsAndRecomputesEnergy() {
        SeamCarver seamCarver = new SeamCarver(solidPicture(4, 4, Color.BLACK));

        seamCarver.removeVerticalSeam(new int[]{1, 1, 1, 1});
        seamCarver.removeHorizontalSeam(new int[]{1, 1, 1});

        Assertions.assertEquals(3, seamCarver.width());
        Assertions.assertEquals(3, seamCarver.height());
        Assertions.assertEquals(195075.0, seamCarver.energy(0, 0));
        Assertions.assertEquals(0.0, seamCarver.energy(1, 1));
    }

    @Test
    void testRejectsInvalidSeams() {
        SeamCarver seamCarver = new SeamCarver(solidPicture(3, 3, Color.BLACK));

        Assertions.assertThrows(NullPointerException.class, () -> seamCarver.removeVerticalSeam(null));
        Assertions.assertThrows(NullPointerException.class, () -> seamCarver.removeHorizontalSeam(null));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> seamCarver.removeVerticalSeam(new int[]{0, 2, 0})
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> seamCarver.removeHorizontalSeam(new int[]{0, 2, 0})
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> seamCarver.removeVerticalSeam(new int[]{0, 0})
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> seamCarver.removeHorizontalSeam(new int[]{0, 0, 3})
        );
    }

    /** The bounds check covers rows as well as columns; only the columns were exercised above. */
    @Test
    void testEnergyValidatesRowsAsWellAsColumns() {
        SeamCarver seamCarver = new SeamCarver(solidPicture(3, 3, Color.BLACK));

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> seamCarver.energy(0, -1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> seamCarver.energy(0, 3));
    }

    /** Carving the last column or row away would leave nothing behind, so it is refused. */
    @Test
    void testRefusesToCarveAwayTheLastColumnOrRow() {
        SeamCarver oneColumn = new SeamCarver(solidPicture(1, 4, Color.BLACK));
        SeamCarver oneRow = new SeamCarver(solidPicture(4, 1, Color.BLACK));

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> oneColumn.removeVerticalSeam(new int[]{0, 0, 0, 0}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> oneRow.removeHorizontalSeam(new int[]{0, 0, 0, 0}));
    }

    /**
     * The remaining halves of the two argument checks: a negative entry in either seam, and a
     * horizontal seam of the wrong length - the vertical case of which is covered above.
     */
    @Test
    void testRejectsNegativeSeamEntriesAndWrongLengthHorizontalSeams() {
        SeamCarver seamCarver = new SeamCarver(solidPicture(3, 3, Color.BLACK));

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> seamCarver.removeVerticalSeam(new int[]{-1, 0, 0}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> seamCarver.removeVerticalSeam(new int[]{0, 0, 3}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> seamCarver.removeHorizontalSeam(new int[]{-1, 0, 0}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> seamCarver.removeHorizontalSeam(new int[]{0, 0}));
    }

    /** Loads a fixture image without depending on the working directory. */
    private static Picture picture(String image) {
        return new Picture(ResourceFiles.resolve(SeamCarver.class, image).toFile());
    }

    private static Picture solidPicture(int width, int height, Color color) {
        Picture picture = new Picture(width, height);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                picture.set(col, row, color);
            }
        }
        return picture;
    }

    private static void assertValidSeam(int[] seam, int expectedLength, int upperBound) {
        Assertions.assertEquals(expectedLength, seam.length);
        for (int index = 0; index < seam.length; index++) {
            Assertions.assertTrue(seam[index] >= 0);
            Assertions.assertTrue(seam[index] < upperBound);
            if (index > 0) {
                Assertions.assertTrue(Math.abs(seam[index] - seam[index - 1]) <= 1);
            }
        }
    }
}
