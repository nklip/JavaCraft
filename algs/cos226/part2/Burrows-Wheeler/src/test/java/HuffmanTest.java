import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

class HuffmanTest {

    @Test
    void testRoundTripsTextAndBinaryData() {
        byte[] text = "ABRACADABRA!".getBytes(StandardCharsets.US_ASCII);
        byte[] binary = {0, 1, 1, 2, 3, 5, 8, 13, -1};

        Assertions.assertArrayEquals(text, expand(compress(text)));
        Assertions.assertArrayEquals(binary, expand(compress(binary)));
    }

    @Test
    void testRoundTripsSingleSymbolAndEmptyInput() {
        byte[] repeated = "AAAAAAAAAA".getBytes(StandardCharsets.US_ASCII);

        Assertions.assertArrayEquals(repeated, expand(compress(repeated)));
        Assertions.assertArrayEquals(new byte[0], expand(compress(new byte[0])));
    }

    @Test
    void testRejectsUnknownCommand() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> Huffman.main(new String[]{"?"}));
    }

    /**
     * A file whose only distinct byte is NUL. Huffman needs at least two symbols to build a trie,
     * so it invents a second one - and which one it invents depends on whether NUL is the symbol
     * already present. Every other single-symbol input takes the other branch.
     */
    @Test
    void testRoundTripsAFileOfNulBytes() {
        byte[] nuls = new byte[7];

        Assertions.assertArrayEquals(nuls, expand(compress(nuls)));
    }

    @Test
    void testMainCompressesWithMinusAndExpandsWithPlus() {
        byte[] text = "ABRACADABRA!".getBytes(StandardCharsets.US_ASCII);

        byte[] compressed = BurrowsWheelerTest.throughStandardStreams(Huffman::main, text, "-");

        Assertions.assertArrayEquals(compress(text), compressed);
        Assertions.assertArrayEquals(
                text, BurrowsWheelerTest.throughStandardStreams(Huffman::main, compressed, "+"));
    }

    @Test
    void testTheStandardStreamEntryPointsCompressAndExpand() {
        byte[] text = "BANANA".getBytes(StandardCharsets.US_ASCII);

        byte[] compressed = BurrowsWheelerTest.throughStandardStreams(
                _ -> Huffman.compress(), text);

        Assertions.assertArrayEquals(compress(text), compressed);
        Assertions.assertArrayEquals(
                text,
                BurrowsWheelerTest.throughStandardStreams(_ -> Huffman.expand(), compressed));
    }

    private static byte[] compress(byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Huffman.compress(
                new BinaryIn(new ByteArrayInputStream(input)),
                new BinaryOut(output)
        );
        return output.toByteArray();
    }

    private static byte[] expand(byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Huffman.expand(
                new BinaryIn(new ByteArrayInputStream(input)),
                new BinaryOut(output)
        );
        return output.toByteArray();
    }
}
