import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author Lipatov Nikita
 */
public class SAPTest {

    @Test
    public void testDigrapth1() {
        String path = "digraph1.txt";
        In in = ResourceFiles.open(SAPTest.class, path);
        Digraph G = new Digraph(in);
        SAP sap = new SAP(G);

        Assertions.assertEquals(1, sap.ancestor(3, 11));
        Assertions.assertEquals(5, sap.ancestor(9, 12));
        Assertions.assertEquals(0, sap.ancestor(7, 2));
        Assertions.assertEquals(-1, sap.ancestor(1, 6));
        Assertions.assertEquals(0, sap.ancestor(0, 2));
        Assertions.assertEquals(10, sap.ancestor(11, 12));

        Assertions.assertEquals(4, sap.length(3, 11));
        Assertions.assertEquals(3, sap.length(9, 12));
        Assertions.assertEquals(4, sap.length(7, 2));
        Assertions.assertEquals(-1, sap.length(1, 6));
        Assertions.assertEquals(2, sap.length(1, 2));
        Assertions.assertEquals(1, sap.length(0, 2));
        Assertions.assertEquals(2, sap.length(11, 12));

        // duplicates
        for (int i = 0; i < 1000; i++) {
            Assertions.assertEquals(1, sap.ancestor(3, 11));
            Assertions.assertEquals(5, sap.ancestor(9, 12));
            Assertions.assertEquals(0, sap.ancestor(7, 2));
            Assertions.assertEquals(-1, sap.ancestor(1, 6));
            Assertions.assertEquals(0, sap.ancestor(0, 2));
            Assertions.assertEquals(10, sap.ancestor(11, 12));

            Assertions.assertEquals(4, sap.length(3, 11));
            Assertions.assertEquals(3, sap.length(9, 12));
            Assertions.assertEquals(4, sap.length(7, 2));
            Assertions.assertEquals(-1, sap.length(1, 6));
            Assertions.assertEquals(2, sap.length(1, 2));
            Assertions.assertEquals(1, sap.length(0, 2));
            Assertions.assertEquals(2, sap.length(11, 12));
        }
    }

    @Test
    public void testDigrapth2() {
        String path = "digraph2.txt";
        In in = ResourceFiles.open(SAPTest.class, path);
        Digraph G = new Digraph(in);
        SAP sap = new SAP(G);

        Assertions.assertEquals(0, sap.ancestor(0, 1));
        Assertions.assertEquals(0, sap.ancestor(5, 0));
        Assertions.assertEquals(2, sap.ancestor(1, 2));
        Assertions.assertEquals(4, sap.ancestor(3, 4));
        Assertions.assertEquals(5, sap.ancestor(4, 5));
        Assertions.assertEquals(0, sap.ancestor(1, 5));
        Assertions.assertEquals(0, sap.ancestor(1, 4));
        Assertions.assertEquals(3, sap.ancestor(1, 3));

        Assertions.assertEquals(1, sap.length(0, 1));
        Assertions.assertEquals(1, sap.length(5, 0));
        Assertions.assertEquals(1, sap.length(1, 2));
        Assertions.assertEquals(1, sap.length(3, 4));
        Assertions.assertEquals(1, sap.length(4, 5));
        Assertions.assertEquals(2, sap.length(1, 5));

        // duplicates
        for (int i = 0; i < 1000; i++) {
            Assertions.assertEquals(0, sap.ancestor(0, 1));
            Assertions.assertEquals(0, sap.ancestor(5, 0));
            Assertions.assertEquals(2, sap.ancestor(1, 2));
            Assertions.assertEquals(4, sap.ancestor(3, 4));
            Assertions.assertEquals(5, sap.ancestor(4, 5));
            Assertions.assertEquals(0, sap.ancestor(1, 5));
            Assertions.assertEquals(0, sap.ancestor(1, 4));
            Assertions.assertEquals(3, sap.ancestor(1, 3));

            Assertions.assertEquals(1, sap.length(0, 1));
            Assertions.assertEquals(1, sap.length(5, 0));
            Assertions.assertEquals(1, sap.length(1, 2));
            Assertions.assertEquals(1, sap.length(3, 4));
            Assertions.assertEquals(1, sap.length(4, 5));
            Assertions.assertEquals(2, sap.length(1, 5));
        }
    }

    @Test
    public void testDigrapth3() {
        String path = "digraph3.txt";
        In in = ResourceFiles.open(SAPTest.class, path);
        Digraph G = new Digraph(in);
        SAP sap = new SAP(G);

        Assertions.assertEquals(1, sap.ancestor(1, 4));
        Assertions.assertEquals(2, sap.ancestor(2, 5));
        Assertions.assertEquals(3, sap.ancestor(3, 6));
        Assertions.assertEquals(8, sap.ancestor(7, 13));
        Assertions.assertEquals(11, sap.ancestor(10, 13));
        Assertions.assertEquals(11, sap.ancestor(11, 13));
        Assertions.assertEquals(12, sap.ancestor(12, 13));
        Assertions.assertEquals(12, sap.ancestor(13, 12));

        Assertions.assertEquals(3, sap.length(1, 4));
        Assertions.assertEquals(3, sap.length(2, 5));
        Assertions.assertEquals(3, sap.length(3, 6));
        Assertions.assertEquals(6, sap.length(7, 13));
        Assertions.assertEquals(4, sap.length(10, 13));
        Assertions.assertEquals(3, sap.length(11, 13));
        Assertions.assertEquals(4, sap.length(12, 13));
        Assertions.assertEquals(4, sap.length(13, 12));

        // duplicates
        for (int i = 0; i < 1000; i++) {
            Assertions.assertEquals(1, sap.ancestor(1, 4));
            Assertions.assertEquals(2, sap.ancestor(2, 5));
            Assertions.assertEquals(3, sap.ancestor(3, 6));
            Assertions.assertEquals(8, sap.ancestor(7, 13));
            Assertions.assertEquals(11, sap.ancestor(10, 13));
            Assertions.assertEquals(11, sap.ancestor(11, 13));
            Assertions.assertEquals(12, sap.ancestor(12, 13));
            Assertions.assertEquals(12, sap.ancestor(13, 12));

            Assertions.assertEquals(3, sap.length(1, 4));
            Assertions.assertEquals(3, sap.length(2, 5));
            Assertions.assertEquals(3, sap.length(3, 6));
            Assertions.assertEquals(6, sap.length(7, 13));
            Assertions.assertEquals(4, sap.length(10, 13));
            Assertions.assertEquals(3, sap.length(11, 13));
            Assertions.assertEquals(4, sap.length(12, 13));
            Assertions.assertEquals(4, sap.length(13, 12));
        }
    }

    @Test
    public void testDigrapth4() {
        String path = "digraph4.txt";
        In in = ResourceFiles.open(SAPTest.class, path);
        Digraph G = new Digraph(in);
        SAP sap = new SAP(G);

        Assertions.assertEquals(8, sap.ancestor(1, 9));
        Assertions.assertEquals(8, sap.ancestor(0, 7));
        Assertions.assertEquals(6, sap.ancestor(3, 0));
        Assertions.assertEquals(6, sap.ancestor(4, 8));

        Assertions.assertEquals(4, sap.length(1, 9));
        Assertions.assertEquals(2, sap.length(0, 7));
        Assertions.assertEquals(5, sap.length(3, 0));
        Assertions.assertEquals(3, sap.length(4, 8));

        // duplicates
        for (int i = 0; i < 1000; i++) {
            Assertions.assertEquals(8, sap.ancestor(1, 9));
            Assertions.assertEquals(8, sap.ancestor(0, 7));
            Assertions.assertEquals(6, sap.ancestor(3, 0));
            Assertions.assertEquals(6, sap.ancestor(4, 8));

            Assertions.assertEquals(4, sap.length(1, 9));
            Assertions.assertEquals(2, sap.length(0, 7));
            Assertions.assertEquals(5, sap.length(3, 0));
            Assertions.assertEquals(3, sap.length(4, 8));
        }
    }

    /**
     * The {@code Iterable} overloads answer -1 when either side is empty, because there is no
     * vertex to start from. {@code WordNet} always passes a non-empty synset set, so nothing else
     * reaches that path.
     */
    @Test
    public void testTheIterableOverloadsHandleEmptyAndNullSets() {
        SAP sap = new SAP(new Digraph(ResourceFiles.open(SAPTest.class, "digraph1.txt")));

        Assertions.assertEquals(-1, sap.length(List.of(), List.of(1)));
        Assertions.assertEquals(-1, sap.ancestor(List.of(), List.of(1)));
        Assertions.assertEquals(-1, sap.length(List.of(3), List.of()));
        Assertions.assertEquals(-1, sap.ancestor(List.of(3), List.of()));

        // every cross pair here is reachable, so the set answer is the best of them: 3 and 9 both
        // reach 5, at one and two steps
        Assertions.assertEquals(3, sap.length(List.of(3, 9), List.of(11, 12)));
        Assertions.assertEquals(5, sap.ancestor(List.of(3, 9), List.of(11, 12)));
        Assertions.assertEquals(1, sap.length(List.of(3, 9), List.of(7, 8)));
        Assertions.assertEquals(3, sap.ancestor(List.of(3, 9), List.of(7, 8)));

        Assertions.assertThrows(NullPointerException.class, () -> sap.length(null, List.of(1)));
        Assertions.assertThrows(NullPointerException.class, () -> sap.ancestor(List.of(1), null));
        Assertions.assertThrows(NullPointerException.class, () -> sap.length(List.of(-1), null));
    }

    @Test
    public void testRejectsInvalidArguments() {
        Digraph digraph = new Digraph(ResourceFiles.open(SAPTest.class, "digraph1.txt"));
        SAP sap = new SAP(digraph);

        Assertions.assertThrows(NullPointerException.class, () -> new SAP(null));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> sap.length(-1, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> sap.ancestor(0, digraph.V()));
        Assertions.assertThrows(
                NullPointerException.class,
                () -> sap.length(Arrays.asList(1, null), List.of(2)));
        Assertions.assertThrows(
                IndexOutOfBoundsException.class,
                () -> sap.ancestor(List.of(1), List.of(digraph.V())));
    }

    /**
     * Vertex 6 of digraph1 is isolated, so any pairing that involves it has no common ancestor.
     * Those pairings have to be skipped rather than counted: their length of -1 is smaller than
     * every real length, so treating them as candidates would make one unreachable pairing decide
     * the answer for the whole cross product. Both sides here reach vertex 0, two steps apart.
     */
    @Test
    public void testAnUnreachablePairingDoesNotDecideTheAnswer() {
        SAP sap = new SAP(new Digraph(ResourceFiles.open(SAPTest.class, "digraph1.txt")));

        Assertions.assertEquals(-1, sap.length(List.of(6), List.of(0)));
        Assertions.assertEquals(2, sap.length(List.of(3, 9, 7, 1), List.of(11, 12, 2, 6)));
        Assertions.assertEquals(0, sap.ancestor(List.of(3, 9, 7, 1), List.of(11, 12, 2, 6)));
    }
}
