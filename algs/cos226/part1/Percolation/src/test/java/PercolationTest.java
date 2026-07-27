import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PercolationTest {

    @Test
    void testRejectsNonPositiveGridSizes() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Percolation(0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Percolation(-1));
    }

    @Test
    void testStartsBlockedAndDoesNotPercolate() {
        Percolation percolation = new Percolation(2);

        Assertions.assertFalse(percolation.isOpen(1, 1));
        Assertions.assertFalse(percolation.isFull(1, 1));
        Assertions.assertFalse(percolation.percolates());
    }

    @Test
    void testOneByOneGridPercolatesAfterOpeningItsSite() {
        Percolation percolation = new Percolation(1);

        percolation.open(1, 1);
        percolation.open(1, 1);

        Assertions.assertTrue(percolation.isOpen(1, 1));
        Assertions.assertTrue(percolation.isFull(1, 1));
        Assertions.assertTrue(percolation.percolates());
    }

    @Test
    void testConnectsAdjacentOpenSites() {
        Percolation percolation = new Percolation(3);

        percolation.open(1, 2);
        percolation.open(2, 2);
        percolation.open(3, 2);

        Assertions.assertTrue(percolation.isFull(3, 2));
        Assertions.assertTrue(percolation.percolates());
    }

    @Test
    void testAvoidsBackwash() {
        Percolation percolation = new Percolation(3);
        percolation.open(1, 1);
        percolation.open(2, 1);
        percolation.open(3, 1);

        percolation.open(3, 3);

        Assertions.assertTrue(percolation.percolates());
        Assertions.assertFalse(percolation.isFull(3, 3));
    }

    @Test
    void testValidatesRowsAndColumnsForEverySiteOperation() {
        Percolation percolation = new Percolation(2);

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.open(0, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.open(3, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.isOpen(1, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.isOpen(1, 3));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.isFull(0, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> percolation.isFull(1, 3));
    }
}
