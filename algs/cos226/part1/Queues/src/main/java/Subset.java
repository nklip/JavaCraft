import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Lipatov Nikita
 */
public class Subset {

    /**
     * How to run: go to console and execute command (IDEA + maven environment):
      Algorithms.Part 1\Queues\target\classes>echo AA BB CC DD FF II KK DD | java -cp .;../../../libs/stdlib.jar Subset 3
     */
    static void main(String[] args) {

        int count = 0;
        if (args != null && args.length >= 1) {
            count = Integer.parseInt(args[0]);
        }

        List<String> input = new ArrayList<>();
        while (!StdIn.isEmpty()) {
            input.add(StdIn.readString());
        }

        for (String item : select(input, count)) {
            StdOut.println(item);
        }
    }

    static List<String> select(Iterable<String> input, int count) {
        Objects.requireNonNull(input, "input");
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }

        RandomizedQueue<String> randomizedQueue = new RandomizedQueue<>();
        input.forEach(randomizedQueue::enqueue);
        while (randomizedQueue.size() > count) {
            randomizedQueue.dequeue();
        }

        List<String> selected = new ArrayList<>(randomizedQueue.size());
        randomizedQueue.forEach(selected::add);
        return selected;
    }
}
