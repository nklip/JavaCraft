import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

class SubsetTest {

    @Test
    void testSelectsRequestedNumberOfInputValues() {
        List<String> input = List.of("AA", "BB", "CC", "DD", "FF");

        List<String> selected = Subset.select(input, 3);

        Assertions.assertEquals(3, selected.size());
        Assertions.assertEquals(3, new HashSet<>(selected).size());
        Assertions.assertTrue(input.containsAll(selected));
    }

    @Test
    void testHandlesZeroAndOversizedSelections() {
        List<String> input = List.of("AA", "BB");

        Assertions.assertTrue(Subset.select(input, 0).isEmpty());
        Assertions.assertEquals(new HashSet<>(input), new HashSet<>(Subset.select(input, 10)));
    }

    @Test
    void testRejectsInvalidInput() {
        Assertions.assertThrows(NullPointerException.class, () -> Subset.select(null, 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Subset.select(List.of("AA"), -1));
        Assertions.assertThrows(
                NullPointerException.class,
                () -> Subset.select(java.util.Arrays.asList("AA", null), 1)
        );
    }
}
