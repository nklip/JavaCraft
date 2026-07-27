import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

class RandomizedQueueTest {

    @Test
    void testStartsEmpty() {
        RandomizedQueue<String> queue = new RandomizedQueue<>();

        Assertions.assertTrue(queue.isEmpty());
        Assertions.assertEquals(0, queue.size());
        Assertions.assertThrows(NoSuchElementException.class, queue::dequeue);
        Assertions.assertThrows(NoSuchElementException.class, queue::sample);
    }

    @Test
    void testRejectsNullItems() {
        RandomizedQueue<String> queue = new RandomizedQueue<>();

        Assertions.assertThrows(NullPointerException.class, () -> queue.enqueue(null));
    }

    @Test
    void testEnqueuesSamplesAndDequeuesEveryValue() {
        RandomizedQueue<String> queue = new RandomizedQueue<>();
        Set<String> expected = Set.of("one", "two", "three", "four");
        expected.forEach(queue::enqueue);

        Assertions.assertTrue(expected.contains(queue.sample()));
        Assertions.assertEquals(4, queue.size());

        Set<String> dequeued = new HashSet<>();
        while (!queue.isEmpty()) {
            dequeued.add(queue.dequeue());
        }

        Assertions.assertEquals(expected, dequeued);
        Assertions.assertEquals(0, queue.size());
    }

    @Test
    void testGrowsBeyondItsInitialCapacity() {
        RandomizedQueue<Integer> queue = new RandomizedQueue<>();
        for (int value = 0; value < 100; value++) {
            queue.enqueue(value);
        }

        Set<Integer> values = new HashSet<>();
        queue.forEach(values::add);

        Assertions.assertEquals(100, queue.size());
        Assertions.assertEquals(100, values.size());
    }

    @Test
    void testIteratorIsAnIndependentSnapshot() {
        RandomizedQueue<String> queue = new RandomizedQueue<>();
        queue.enqueue("one");
        queue.enqueue("two");
        queue.enqueue("three");
        Iterator<String> iterator = queue.iterator();

        queue.dequeue();
        queue.enqueue("four");

        List<String> snapshot = new ArrayList<>();
        iterator.forEachRemaining(snapshot::add);
        Assertions.assertEquals(Set.of("one", "two", "three"), new HashSet<>(snapshot));
    }

    @Test
    void testIteratorRejectsUnsupportedOperationsAndExhaustion() {
        RandomizedQueue<String> queue = new RandomizedQueue<>();
        queue.enqueue("value");
        Iterator<String> iterator = queue.iterator();

        Assertions.assertEquals("value", iterator.next());
        Assertions.assertFalse(iterator.hasNext());
        Assertions.assertThrows(NoSuchElementException.class, iterator::next);
        Assertions.assertThrows(UnsupportedOperationException.class, iterator::remove);
    }
}
