import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * @author Lipatov Nikita
 */
public class Deque<Item> implements Iterable<Item> {

    private Node<Item> first = null;
    private Node<Item> last = null;
    private int size;

    // construct an empty deque
    public Deque() {
        size = 0;
    }

    // is the deque empty?
    public boolean isEmpty() {
        return (size() == 0);
    }

    // return the number of items on the deque
    public int size() {
        return this.size;
    }

    // insert the item at the front
    public void addFirst(Item item) {
        if (item == null) {
            throw new NullPointerException("Item is null!");
        }
        if (first == null) {
            first = new Node<>(null, item, null);
            last = first;
        } else {
            Node<Item> temp = first; // current node
            first = new Node<>(null, item, temp); // new node
            temp.prev = first;
        }
        size++; // last node
    }

    // insert the item at the end
    public void addLast(Item item) {
        if (item == null) {
            throw new NullPointerException("Item is null!");
        }
        if (last == null) {
            last = new Node<>(first, item, null);
            first = last;
        } else {
            Node<Item> temp = last; // current node
            last = new Node<>(temp, item, null); // new node
            temp.next = last;
        }
        size++;
    }

    // delete and return the item at the front
    public Item removeFirst() {
        if (first == null) {
            throw new NoSuchElementException("First Item in Deque is empty!");
        }
        Node<Item> temp;
        size--;
        if (first.next != null && first != first.next) {
            temp = first;
            first = first.next;
            first.prev = null;
            return temp.item;
        } else {
            temp = first;
            first = null;
            last = null;
            return temp.item;
        }
    }

    // delete and return the item at the end
    public Item removeLast() {
        if (last == null) {
            throw new NoSuchElementException("Last Item in Deque is empty!");
        }
        Node<Item> temp;
        size--;
        if (last.prev != null && last != last.prev) {
            temp = last;
            last = last.prev;
            last.next = null;
            return temp.item;
        } else {
            temp = last;
            first = null;
            last = null;
            return temp.item;
        }
    }

    // return an iterator over items in order from front to end
    @Override
    public Iterator<Item> iterator() {
        return new DequeItr(first);
    }

    private static final class Node<Item> {
        private final Item item;
        private Node<Item> next;
        private Node<Item> prev;

        private Node(Node<Item> prev, Item element, Node<Item> next) {
            this.item = element;
            this.next = next;
            this.prev = prev;
        }
    }

    private class DequeItr implements Iterator<Item> {

        private Node<Item> currentItem;

        private DequeItr(Node<Item> item) {
            currentItem = item;
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
            Item item = currentItem.item;
            currentItem = currentItem.next;
            return item;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
