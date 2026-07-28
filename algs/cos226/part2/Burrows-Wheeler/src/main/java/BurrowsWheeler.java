import java.util.function.IntConsumer;

/**
 * @author Lipatov Nikita
 * <p>
 * Complexity notation: {@code N} is the number of input bytes and {@code R} is the alphabet
 * size.
 */
public class BurrowsWheeler {
    private static final int R = 256;

    /**
     * Required time complexity: {@code O(N + R)}, excluding circular suffix array construction.
     * Actual time complexity: {@code O(N)} excluding that construction and
     * {@code O(N log N + R)} overall.
     */
    public static void encode() {
        String input = BinaryStdIn.isEmpty() ? "" : BinaryStdIn.readString();
        encode(
                input,
                BinaryStdOut::write,
                character -> BinaryStdOut.write((char) character, 8)
        );
        BinaryStdOut.close();
    }

    static void encode(BinaryIn binaryIn, BinaryOut binaryOut) {
        String input = binaryIn.isEmpty() ? "" : binaryIn.readString();
        encode(
                input,
                binaryOut::write,
                character -> binaryOut.write((char) character, 8)
        );
        binaryOut.close();
    }

    private static void encode(
            String input,
            IntConsumer firstOutput,
            IntConsumer characterOutput
    ) {
        char[] characters = input.toCharArray();

        CircularSuffixArray csa = new CircularSuffixArray(input);

        int first = 0;
        for (int i = 0; i < csa.length(); i++) {
            if (csa.index(i) == 0) {
                first = i;
                break;
            }
        }
        firstOutput.accept(first);

        for (int i = 0; i < input.length(); i++) {
            int idx = (csa.index(i) + csa.length() - 1) % csa.length();
            characterOutput.accept(characters[idx]);
        }
    }

    /**
     * Required time complexity: {@code O(N + R)} in the worst case.
     * Actual time complexity: {@code O(N + R)}.
     */
    public static void decode() {
        int first = BinaryStdIn.readInt();
        String input = BinaryStdIn.isEmpty() ? "" : BinaryStdIn.readString();
        decode(first, input, character -> BinaryStdOut.write((char) character, 8));
        BinaryStdOut.close();
    }

    static void decode(BinaryIn binaryIn, BinaryOut binaryOut) {
        int first = binaryIn.readInt();
        String input = binaryIn.isEmpty() ? "" : binaryIn.readString();
        decode(first, input, character -> binaryOut.write((char) character, 8));
        binaryOut.close();
    }

    private static void decode(int first, String transformed, IntConsumer characterOutput) {
        char[] input = transformed.toCharArray();
        char[] sorted = new char[input.length];

        if (input.length == 0) {
            if (first != 0) {
                throw new IllegalArgumentException("invalid first index");
            }
            return;
        }
        if (first < 0 || first >= input.length) {
            throw new IllegalArgumentException("invalid first index");
        }

        int[] counts = new int[R + 1];
        for (char character : input) {
            counts[character + 1]++;
        }
        for (int character = 0; character < R; character++) {
            counts[character + 1] += counts[character];
        }

        int[] next = new int[input.length];
        for (int i = 0; i < input.length; i++) {
            char character = input[i];
            int sortedIndex = counts[character]++;
            sorted[sortedIndex] = character;
            next[sortedIndex] = i;
        }

        // show the string.
        int i;
        int ptr;
        for (i = 0, ptr = first; i < next.length; i++, ptr = next[ptr]) {
            characterOutput.accept(sorted[ptr]);
        }
    }

    /**
     *
     * How to run:
     java -classpath .;../../../libs/* BurrowsWheeler - < abra.txt | java -classpath .;../../../libs/* HexDump 16
     ER:
     00 00 00 03 41 52 44 21 52 43 41 41 41 41 42 42
     128 bits
     *
     * <p>
     * Required time complexity:
     * {@code O(N + R)} for either transform, excluding circular suffix array construction when encoding.
     * Actual time complexity:
     * {@code O(N log N + R)} for encoding and
     * {@code O(N + R)} for decoding.
     */
    static void main(String[] args) {
        if (args != null && args.length >= 1) {
            String arg = args[0];
            if (arg.equals("-")) {
                encode();
            } else if (arg.equals("+")) {
                decode();
            }
        } else { // default case
            System.out.println("Nothing is found!");
        }
    }
}
