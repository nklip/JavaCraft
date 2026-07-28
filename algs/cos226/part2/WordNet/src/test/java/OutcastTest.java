import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OutcastTest {

    private static Outcast outcast;

    @BeforeAll
    static void setUp() {
        outcast = new Outcast(new WordNet("synsets.txt", "hypernyms.txt"));
    }

    @Test
    void testFindsLeastRelatedNoun() {
        Assertions.assertEquals(
                "table",
                outcast.outcast(new String[]{"horse", "zebra", "cat", "bear", "table"})
        );
        Assertions.assertEquals(
                "bed",
                outcast.outcast(new String[]{
                        "water", "soda", "bed", "orange_juice",
                        "milk", "apple_juice", "tea", "coffee"
                })
        );
        Assertions.assertEquals(
                "potato",
                outcast.outcast(new String[]{
                        "apple", "pear", "peach", "banana", "lime", "lemon",
                        "blueberry", "strawberry", "mango", "watermelon", "potato"
                })
        );
    }

    @Test
    void testReturnsANounWhenAllDistancesAreZero() {
        Assertions.assertEquals("bird", outcast.outcast(new String[]{"bird", "fowl"}));
    }

    /**
     * The test client, which builds its own WordNet from the default file names and prints one
     * line per outcast file. The output goes through {@code StdOut}, which caches
     * {@code System.out} when its class is initialized, so only that it runs is asserted.
     */
    @Test
    void testMainRunsOverItsDefaultFilesAndOverExplicitOnes() {
        Assertions.assertDoesNotThrow(() -> Outcast.main(null));
        Assertions.assertDoesNotThrow(() -> Outcast.main(new String[0]));
        Assertions.assertDoesNotThrow(() -> Outcast.main(
                new String[]{"synsets.txt", "hypernyms.txt", "outcast5.txt", "outcast8.txt"}));
    }
}
