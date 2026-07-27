import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class KdTreeTest {

    private KdTree tree;

    @BeforeEach
    void setUp() {
        tree = new KdTree();
    }

    @Test
    void testStartsEmpty() {
        Assertions.assertTrue(tree.isEmpty());
        Assertions.assertEquals(0, tree.size());
        Assertions.assertFalse(tree.contains(new Point2D(0.5, 0.5)));
        Assertions.assertNull(tree.nearest(new Point2D(0.5, 0.5)));
        Assertions.assertFalse(tree.range(new RectHV(0.0, 0.0, 1.0, 1.0)).iterator().hasNext());
        Assertions.assertDoesNotThrow(tree::draw);
    }

    @Test
    void testInsertsPointsAndIgnoresDuplicates() {
        Point2D point = new Point2D(0.7, 0.2);

        tree.insert(point);
        tree.insert(point);

        Assertions.assertFalse(tree.isEmpty());
        Assertions.assertEquals(1, tree.size());
        Assertions.assertTrue(tree.contains(point));
    }

    @Test
    void testFindsPointsOnBothSidesOfAlternatingSplits() {
        populate(tree);

        Assertions.assertTrue(tree.contains(new Point2D(0.2, 0.3)));
        Assertions.assertTrue(tree.contains(new Point2D(0.9, 0.6)));
        Assertions.assertFalse(tree.contains(new Point2D(0.1, 0.1)));
        Assertions.assertEquals(5, tree.size());
    }

    @Test
    void testReturnsOnlyPointsInsideRange() {
        populate(tree);

        Set<Point2D> actual = new HashSet<>();
        tree.range(new RectHV(0.0, 0.0, 0.5, 0.5)).forEach(actual::add);

        Assertions.assertEquals(
                Set.of(new Point2D(0.5, 0.4), new Point2D(0.2, 0.3)),
                actual
        );
    }

    @Test
    void testFindsNearestPointAcrossRepeatedQueries() {
        populate(tree);

        Assertions.assertEquals(new Point2D(0.7, 0.2), tree.nearest(new Point2D(0.65, 0.25)));
        Assertions.assertEquals(new Point2D(0.4, 0.7), tree.nearest(new Point2D(0.35, 0.75)));
    }

    @Test
    void testRejectsNullArguments() {
        Assertions.assertThrows(NullPointerException.class, () -> tree.insert(null));
        Assertions.assertThrows(NullPointerException.class, () -> tree.contains(null));
        Assertions.assertThrows(NullPointerException.class, () -> tree.range(null));
        Assertions.assertThrows(NullPointerException.class, () -> tree.nearest(null));
    }

    private static void populate(KdTree tree) {
        tree.insert(new Point2D(0.7, 0.2));
        tree.insert(new Point2D(0.5, 0.4));
        tree.insert(new Point2D(0.2, 0.3));
        tree.insert(new Point2D(0.4, 0.7));
        tree.insert(new Point2D(0.9, 0.6));
    }
}
