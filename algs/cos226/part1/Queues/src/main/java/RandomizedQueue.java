import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * @author Lipatov Nikita
 */
public class RandomizedQueue<Item> implements Iterable<Item> {

    private static final int DEFAULT_SIZE = 32;

    private Object[] items;
    private int size;

    // construct an empty randomized queue
    public RandomizedQueue() {
        items = new Object[DEFAULT_SIZE];
    }

    private void resize(int newCapacity) {
        Object[] temp = new Object[newCapacity];
        System.arraycopy(items, 0, temp, 0, items.length);
        items = temp;
    }

    // is the queue empty?
    public boolean isEmpty() {
        return (size() == 0);
    }

    // return the number of items on the queue
    public int size() {
        return size;
    }

    // add the item
    public void enqueue(Item item) {
        if (item == null) {
            throw new NullPointerException("Item is null!");
        }
        if (size() > items.length * 0.75) {
            resize(items.length * 2);
        }
        items[size++] = item;
    }

    // delete and return a random item
    public Item dequeue() {
        if (isEmpty()) {
            throw new NoSuchElementException("Queue is empty!");
        }
        int random = StdRandom.uniform(size);
        Item temp = itemAt(items, random);
        if (random + 1 != size) {
            items[random] = items[size - 1];
        }
        items[size - 1] = null;
        size--;
        return temp;
    }

    // return (but do not delete) a random item
    public Item sample() {
        if (isEmpty()) {
            throw new NoSuchElementException("Queue is empty!");
        }
        int random = StdRandom.uniform(size);
        return itemAt(items, random);
    }

    // return an independent iterator over items in random order
    @Override
    public Iterator<Item> iterator() {
        return new RandomizedQueueItr(items, size);
    }

    @SuppressWarnings("unchecked")
    private Item itemAt(Object[] source, int index) {
        return (Item) source[index];
    }

    private class RandomizedQueueItr implements Iterator<Item> {

        private final Object[] queue;
        private Item currentItem;
        private int index;

        private RandomizedQueueItr(Object[] queue, int size) {
            Object[] temp = new Object[size];
            System.arraycopy(queue, 0, temp, 0, size);
            StdRandom.shuffle(temp);
            this.queue = temp;
            index = 0;
            if (size != 0) {
                currentItem = itemAt(this.queue, index++);
            }
        }

        @Override
        public boolean hasNext() {
            return currentItem != null;
        }

        @Override
        public Item next() {
            if (currentItem == null) {
                throw new NoSuchElementException();
            }
            Item temp = currentItem;
            if (index == queue.length) {
                currentItem = null;
            } else {
                currentItem = itemAt(queue, index++);
            }
            return temp;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
