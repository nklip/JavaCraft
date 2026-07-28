import java.util.Iterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author Lipatov Nikita
 */
public class WordNetTest {

    @Test
    public void testParse() {
        WordNet wordNet = new WordNet("synsets.txt", "hypernyms.txt");

        Assertions.assertTrue(wordNet.isNoun("bird"));
        Assertions.assertFalse(wordNet.isNoun("not_a_wordnet_noun"));
        Assertions.assertTrue(wordNet.nouns().iterator().hasNext());
    }

    @Test
    public void testDistance() {
        WordNet wordNet = new WordNet("synsets.txt", "hypernyms.txt");
        Assertions.assertEquals(23, wordNet.distance("white_marlin", "mileage"));
        Assertions.assertEquals(33, wordNet.distance("Black_Plague", "black_marlin"));
        Assertions.assertEquals(27, wordNet.distance("American_water_spaniel", "histology"));
        Assertions.assertEquals(29, wordNet.distance("Brown_Swiss", "barrel_roll"));
    }

    @Test
    public void testRejectsNullArguments() {
        WordNet wordNet = new WordNet("synsets.txt", "hypernyms.txt");

        Assertions.assertThrows(NullPointerException.class, () -> new WordNet(null, "hypernyms.txt"));
        Assertions.assertThrows(NullPointerException.class, () -> new WordNet("synsets.txt", null));
        Assertions.assertThrows(NullPointerException.class, () -> wordNet.isNoun(null));
        Assertions.assertThrows(NullPointerException.class, () -> wordNet.distance(null, "bird"));
        Assertions.assertThrows(NullPointerException.class, () -> wordNet.distance("bird", null));
        Assertions.assertThrows(NullPointerException.class, () -> wordNet.distance("not-a-noun", null));
        Assertions.assertThrows(NullPointerException.class, () -> wordNet.sap(null, "bird"));
        Assertions.assertThrows(NullPointerException.class, () -> wordNet.sap("bird", null));
        Assertions.assertThrows(NullPointerException.class, () -> wordNet.sap("not-a-noun", null));
    }

    @Test
    public void testNounsCannotMutateWordNet() {
        WordNet wordNet = new WordNet("synsets.txt", "hypernyms.txt");
        Iterator<String> nouns = wordNet.nouns().iterator();
        String noun = nouns.next();

        Assertions.assertThrows(UnsupportedOperationException.class, nouns::remove);
        Assertions.assertTrue(wordNet.isNoun(noun));
    }

    @Test
    public void testAncestor() {
        WordNet wordNet = new WordNet("synsets.txt", "hypernyms.txt");
        Assertions.assertEquals("physical_entity", wordNet.sap("individual", "edible_fruit"));
        Assertions.assertEquals("region", wordNet.sap("administrative_district", "populated_area"));
    }

    @Test
    public void testMainRunsWithInputFiles() {
        Assertions.assertDoesNotThrow(
                () -> WordNet.main(new String[]{"synsets6.txt", "hypernyms6TwoAncestors.txt"}));
    }
}
