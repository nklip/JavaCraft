import java.util.LinkedList;
import java.util.function.IntConsumer;

/**
 * @author Lipatov Nikita
 *
 * <p>Complexity notation: {@code N} is the number of input bytes and {@code R} is the alphabet
 * size.
 */
public class MoveToFront {

    private static final int R = 256;

    /**
     * Required time complexity: {@code O(R * N)} or better in the worst case and
     * {@code O(N + R)} or better in practice for typical transformed English text.
     * Actual time complexity: {@code O(R + R * N)} in the worst case and {@code O(N + R)}
     * when accessed ranks stay near the front.
     */
    public static void encode() {
        String input = BinaryStdIn.isEmpty() ? "" : BinaryStdIn.readString();
        encode(input, character -> BinaryStdOut.write((char) character, 8));
        BinaryStdOut.close();
    }

    static void encode(BinaryIn binaryIn, BinaryOut binaryOut) {
        String input = binaryIn.isEmpty() ? "" : binaryIn.readString();
        encode(input, character -> binaryOut.write((char) character, 8));
        binaryOut.close();
    }

    private static void encode(String input, IntConsumer characterOutput) {
        // Store the list of chars.
        LinkedList<Integer> storeList = new LinkedList<>();
        for (int i = 0; i < R; i++) {
            storeList.add(i);
        }
        // Check whether the char is in the list.
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int index = storeList.indexOf((int) c);
            characterOutput.accept(index);
            int remObj = storeList.remove(index);
            storeList.addFirst(remObj);
        }
    }

    /**
     * Required time complexity: {@code O(R * N)} or better in the worst case and
     * {@code O(N + R)} or better in practice for typical transformed English text.
     * Actual time complexity: {@code O(R + R * N)} in the worst case and {@code O(N + R)}
     * when encoded ranks stay near the front.
     */
    public static void decode() {
        String input = BinaryStdIn.isEmpty() ? "" : BinaryStdIn.readString();
        decode(input, character -> BinaryStdOut.write((char) character, 8));
        BinaryStdOut.close();
    }

    static void decode(BinaryIn binaryIn, BinaryOut binaryOut) {
        String input = binaryIn.isEmpty() ? "" : binaryIn.readString();
        decode(input, character -> binaryOut.write((char) character, 8));
        binaryOut.close();
    }

    private static void decode(String input, IntConsumer characterOutput) {
        LinkedList<Integer> storeList = new LinkedList<>();
        for (int i = 0; i < R; i++) {
            storeList.add(i);
        }

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int index = storeList.remove(c);
            storeList.addFirst(index);
            characterOutput.accept(index);
        }

        // Total, worst, R*N, Best, N
    }

    /**
     * How to run this:
     * 1) compile all classes from default package
     * 2) put file abra.txt in target/classes directory
     * 3) go to console in target/classes directory and run next command (Windows env):

     java -classpath .;../../../libs/* Huffman - < abra.txt | java -classpath .;../../../libs/* HexDump 16
     ER:
     50 4a 22 43 43 54 a8 40 00 00 01 8f 96 8f 94
     120 bits

        or this

     java -classpath .;../../../libs/* Huffman - < abra.txt | java -classpath .;../../../libs/* Huffman +
     ER:
     ABRACADABRA!

     * <p>
     * Required time complexity: {@code O(R * N)} or better in the worst case and
     * {@code O(N + R)} or better for typical transformed English text.
     * Actual worst-case time complexity: {@code O(R + R * N)} for either mode.
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
