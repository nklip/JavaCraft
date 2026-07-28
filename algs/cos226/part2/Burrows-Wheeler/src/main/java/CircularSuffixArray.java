import java.util.Arrays;

/**
 * @author Lipatov Nikita
 *
 * <p>Complexity notation: {@code N} is the string length and {@code R} is the character
 * alphabet range.
 */
public class CircularSuffixArray {

    private final int[] array;
    private final int size;

    /**
     * Required time complexity: {@code O(N log N)} or better on typical English text.
     * Actual worst-case time complexity: {@code O(N log N + R)}.
     */
    public CircularSuffixArray(String source) {
        if (source == null) {
            throw new NullPointerException("The argument of CircularSuffixArray is null!");
        }
        this.size = source.length();
        this.array = sort(source);
    }

    /**
     * Required time complexity: {@code O(1)} in the worst case.
     * Actual time complexity: {@code O(1)}.
     */
    public int length() {
        return this.size;
    }

    /**
     * Required time complexity: {@code O(1)} in the worst case.
     * Actual time complexity: {@code O(1)}.
     */
    public int index(int i) {
        if (i >= length()) {
            throw new IndexOutOfBoundsException();
        }
        return array[i];
    }

    /**
     * Sorts circular suffixes by doubling the number of significant characters in each pass.
     * Every pass counting-sorts integer start positions by the equivalence classes from the
     * previous pass, so no rotated strings are created.
     */
    private static int[] sort(String source) {
        int length = source.length();
        if (length == 0) {
            return new int[0];
        }

        int[] order = sortByFirstCharacter(source);
        int[] classes = new int[length];
        int classCount = assignFirstCharacterClasses(source, order, classes);
        int[] shifted = new int[length];
        int[] nextClasses = new int[length];
        int[] counts = new int[length];

        int offset = 1;
        while (offset < length && classCount < length) {
            for (int i = 0; i < length; i++) {
                int index = order[i] - offset;
                shifted[i] = index < 0 ? index + length : index;
            }

            Arrays.fill(counts, 0, classCount, 0);
            for (int index : shifted) {
                counts[classes[index]]++;
            }
            for (int i = 1; i < classCount; i++) {
                counts[i] += counts[i - 1];
            }
            for (int i = length - 1; i >= 0; i--) {
                int index = shifted[i];
                order[--counts[classes[index]]] = index;
            }

            int nextClassCount = 1;
            nextClasses[order[0]] = 0;
            for (int i = 1; i < length; i++) {
                int current = order[i];
                int previous = order[i - 1];
                int currentSecondHalf = (current + offset) % length;
                int previousSecondHalf = (previous + offset) % length;
                if (classes[current] != classes[previous]
                        || classes[currentSecondHalf] != classes[previousSecondHalf]) {
                    nextClassCount++;
                }
                nextClasses[current] = nextClassCount - 1;
            }

            int[] previousClasses = classes;
            classes = nextClasses;
            nextClasses = previousClasses;
            classCount = nextClassCount;
            offset = offset > length / 2 ? length : offset * 2;
        }

        orderByClassThenIndex(order, classes, classCount, counts);
        return order;
    }

    private static int[] sortByFirstCharacter(String source) {
        int maxCharacter = 0;
        for (int i = 0; i < source.length(); i++) {
            maxCharacter = Math.max(maxCharacter, source.charAt(i));
        }

        int[] counts = new int[maxCharacter + 1];
        for (int i = 0; i < source.length(); i++) {
            counts[source.charAt(i)]++;
        }
        for (int i = 1; i < counts.length; i++) {
            counts[i] += counts[i - 1];
        }

        int[] order = new int[source.length()];
        for (int i = source.length() - 1; i >= 0; i--) {
            char character = source.charAt(i);
            order[--counts[character]] = i;
        }
        return order;
    }

    private static int assignFirstCharacterClasses(String source, int[] order, int[] classes) {
        int classCount = 1;
        classes[order[0]] = 0;
        for (int i = 1; i < order.length; i++) {
            if (source.charAt(order[i]) != source.charAt(order[i - 1])) {
                classCount++;
            }
            classes[order[i]] = classCount - 1;
        }
        return classCount;
    }

    /**
     * Equal circular suffixes are ordered by their original index. The assignment permits either
     * order, but making the tie-break deterministic also preserves the behavior of the old stable
     * sort and keeps transforms byte-for-byte reproducible.
     */
    private static void orderByClassThenIndex(
            int[] order,
            int[] classes,
            int classCount,
            int[] positions
    ) {
        Arrays.fill(positions, 0, classCount, 0);
        for (int suffixClass : classes) {
            positions[suffixClass]++;
        }

        int nextPosition = 0;
        for (int i = 0; i < classCount; i++) {
            int classSize = positions[i];
            positions[i] = nextPosition;
            nextPosition += classSize;
        }
        for (int index = 0; index < classes.length; index++) {
            order[positions[classes[index]]++] = index;
        }
    }
}
