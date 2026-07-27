import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

class DequeTest {

    @Test
    void testStartsEmpty() {
        Deque<String> deque = new Deque<>();

        Assertions.assertTrue(deque.isEmpty());
        Assertions.assertEquals(0, deque.size());
        Assertions.assertThrows(NoSuchElementException.class, deque::removeFirst);
        Assertions.assertThrows(NoSuchElementException.class, deque::removeLast);
    }

    @Test
    void testAddsAndRemovesAtBothEnds() {
        Deque<String> deque = new Deque<>();
        deque.addFirst("middle");
        deque.addFirst("first");
        deque.addLast("last");

        Assertions.assertEquals(List.of("first", "middle", "last"), values(deque));
        Assertions.assertEquals("first", deque.removeFirst());
        Assertions.assertEquals("last", deque.removeLast());
        Assertions.assertEquals("middle", deque.removeFirst());
        Assertions.assertTrue(deque.isEmpty());
    }

    @Test
    void testCanBeReusedAfterBecomingEmpty() {
        Deque<String> deque = new Deque<>();
        deque.addFirst("first");
        Assertions.assertEquals("first", deque.removeLast());

        deque.addLast("second");

        Assertions.assertEquals("second", deque.removeFirst());
        Assertions.assertTrue(deque.isEmpty());
    }

    @Test
    void testRejectsNullItems() {
        Deque<String> deque = new Deque<>();

        Assertions.assertThrows(NullPointerException.class, () -> deque.addFirst(null));
        Assertions.assertThrows(NullPointerException.class, () -> deque.addLast(null));
    }

    @Test
    void testIteratorRejectsUnsupportedOperationsAndExhaustion() {
        Deque<String> deque = new Deque<>();
        deque.addLast("value");
        Iterator<String> iterator = deque.iterator();

        Assertions.assertEquals("value", iterator.next());
        Assertions.assertFalse(iterator.hasNext());
        Assertions.assertThrows(NoSuchElementException.class, iterator::next);
        Assertions.assertThrows(UnsupportedOperationException.class, iterator::remove);
    }

    private static <T> List<T> values(Deque<T> deque) {
        List<T> values = new ArrayList<>();
        deque.forEach(values::add);
        return values;
    }
}
