import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives the whole compression pipeline from the files in {@code src/test/resources}.
 *
 * <p>The fixtures carry their own expected answers in their names. For a source file {@code X},
 * {@code X.bwt} is the Burrows-Wheeler transform of it, {@code X.mtf} the move-to-front encoding,
 * {@code X.huf} the Huffman compression, and {@code X.bwt.mtf.huf} all three in sequence - which
 * is the pipeline the assignment is actually about. Three sources ship with a full set:
 * {@code abra.txt}, {@code us.gif} and {@code aesop.txt}. Comparing byte for byte against those is
 * a far stronger check than a round trip, because a transform that is wrong but self-consistent
 * still round-trips.
 *
 * <p>Round trips cover the fixtures the stage files do not: every source that can be encoded has
 * to survive encode-then-decode unchanged, including the binary ones, where any assumption that
 * the input is text would show up.
 */
class CompressionPipelineFilesTest {

    /** The three sources shipping a complete set of {@code .bwt}, {@code .mtf}, {@code .huf}. */
    static Stream<String> staged() {
        return Stream.of("abra.txt", "us.gif", "aesop.txt");
    }

    // ---------- stage outputs against the shipped files ----------

    @ParameterizedTest(name = "burrows-wheeler encode of {0} matches {0}.bwt")
    @MethodSource("staged")
    void testBurrowsWheelerEncodeMatchesTheShippedTransform(String source) {
        Assertions.assertArrayEquals(
                read(source + ".bwt"), apply(BurrowsWheeler::encode, read(source)), source);
    }

    @ParameterizedTest(name = "burrows-wheeler decode of {0}.bwt reproduces {0}")
    @MethodSource("staged")
    void testBurrowsWheelerDecodeReproducesTheSource(String source) {
        Assertions.assertArrayEquals(
                read(source), apply(BurrowsWheeler::decode, read(source + ".bwt")), source);
    }

    @ParameterizedTest(name = "move-to-front encode of {0} matches {0}.mtf")
    @MethodSource("staged")
    void testMoveToFrontEncodeMatchesTheShippedEncoding(String source) {
        Assertions.assertArrayEquals(
                read(source + ".mtf"), apply(MoveToFront::encode, read(source)), source);
    }

    @ParameterizedTest(name = "move-to-front decode of {0}.mtf reproduces {0}")
    @MethodSource("staged")
    void testMoveToFrontDecodeReproducesTheSource(String source) {
        Assertions.assertArrayEquals(
                read(source), apply(MoveToFront::decode, read(source + ".mtf")), source);
    }

    @ParameterizedTest(name = "huffman compression of {0} matches {0}.huf")
    @MethodSource("staged")
    void testHuffmanCompressionMatchesTheShippedArchive(String source) {
        Assertions.assertArrayEquals(
                read(source + ".huf"), apply(Huffman::compress, read(source)), source);
    }

    @ParameterizedTest(name = "huffman expansion of {0}.huf reproduces {0}")
    @MethodSource("staged")
    void testHuffmanExpansionReproducesTheSource(String source) {
        Assertions.assertArrayEquals(
                read(source), apply(Huffman::expand, read(source + ".huf")), source);
    }

    /**
     * The three stages chained, which is what the assignment is for. The transform is taken from
     * the shipped {@code .bwt} rather than recomputed, so {@code aesop.txt} can be included.
     */
    @ParameterizedTest(name = "the full pipeline over {0} matches {0}.bwt.mtf.huf")
    @MethodSource("staged")
    void testTheFullPipelineMatchesTheShippedArchive(String source) {
        byte[] compressed = apply(Huffman::compress,
                apply(MoveToFront::encode, read(source + ".bwt")));

        Assertions.assertArrayEquals(read(source + ".bwt.mtf.huf"), compressed, source);
    }

    /** And the same three stages run backwards, which has to give the original file back. */
    @ParameterizedTest(name = "expanding {0}.bwt.mtf.huf reproduces {0}")
    @MethodSource("staged")
    void testTheFullPipelineExpandsBackToTheSource(String source) {
        byte[] expanded = apply(BurrowsWheeler::decode,
                apply(MoveToFront::decode,
                        apply(Huffman::expand, read(source + ".bwt.mtf.huf"))));

        Assertions.assertArrayEquals(read(source), expanded, source);
    }

    /**
     * On English prose the pipeline is the point: {@code aesop.txt} goes from 191,943 bytes to
     * 66,026, where Huffman on its own only reaches 107,988. Sorting the rotations groups repeated
     * context together, move-to-front turns that into runs of small numbers, and Huffman then has
     * a very skewed distribution to work with.
     */
    @Test
    void testThePipelineCompressesProseFarBetterThanHuffmanAlone() {
        int original = read("aesop.txt").length;
        int huffmanOnly = read("aesop.txt.huf").length;
        int pipeline = read("aesop.txt.bwt.mtf.huf").length;

        Assertions.assertTrue(huffmanOnly < original, "huffman alone should still compress prose");
        Assertions.assertTrue(
                pipeline < huffmanOnly,
                () -> "pipeline " + pipeline + " should beat huffman " + huffmanOnly);
        Assertions.assertTrue(
                pipeline * 2 < original,
                () -> "pipeline should more than halve " + original + ", got " + pipeline);
    }

    /**
     * The other side of it, and the reason the assertion above names one fixture rather than
     * looping over all three. A GIF is already compressed, so there is no redundancy left to find
     * and every stage can only add its own overhead - {@code us.gif} grows from 12,400 bytes to
     * 12,693 under Huffman and 12,726 through the pipeline. {@code abra.txt} grows too, for the
     * different reason that twelve bytes cannot pay for a Huffman trie.
     */
    @ParameterizedTest(name = "{0} is not made smaller by compressing it")
    @ValueSource(strings = {"us.gif", "abra.txt"})
    void testAlreadyCompressedAndTinyInputsOnlyGrow(String source) {
        int original = read(source).length;

        Assertions.assertTrue(
                read(source + ".huf").length > original,
                () -> source + ": huffman unexpectedly compressed it");
        Assertions.assertTrue(
                read(source + ".bwt.mtf.huf").length > original,
                () -> source + ": the pipeline unexpectedly compressed it");
    }

    // ---------- round trips ----------

    @ParameterizedTest(name = "burrows-wheeler round-trips {0}")
    @ValueSource(strings = {
            "a.txt", "zebra.txt", "couscous.txt", "abra.txt", "cadabra.txt", "stars.txt",
            "encodedSecretMessage.txt", "alphanum.txt", "rand10K.bin", "us.gif",
            "amendments.txt", "CS_bricks.jpg"
    })
    void testBurrowsWheelerRoundTripsEveryEncodableFixture(String source) {
        byte[] original = read(source);

        Assertions.assertArrayEquals(
                original, apply(BurrowsWheeler::decode, apply(BurrowsWheeler::encode, original)), source);
    }

    /**
     * Move-to-front and Huffman need no suffix array, so the large binary fixtures are in reach
     * here - {@code purple.gif} is 1.3 MB. {@code dickens.txt} is left out at 29 MB.
     */
    @ParameterizedTest(name = "move-to-front and huffman round-trip {0}")
    @ValueSource(strings = {
            "a.txt", "abra.txt", "alphanum.txt", "rand10K.bin", "us.gif", "amendments.txt",
            "CS_bricks.jpg", "aesop.txt", "purple.gif"
    })
    void testMoveToFrontAndHuffmanRoundTripEveryFixture(String source) {
        byte[] original = read(source);

        Assertions.assertArrayEquals(
                original, apply(MoveToFront::decode, apply(MoveToFront::encode, original)),
                source + " through move-to-front");
        Assertions.assertArrayEquals(
                original, apply(Huffman::expand, apply(Huffman::compress, original)),
                source + " through huffman");
    }

    // ---------- the fixtures that speak for themselves ----------

    /**
     * {@code encodedSecretMessage.txt} is a Burrows-Wheeler transform whose plain text is the point
     * of the fixture, so the expected answer needs no oracle at all.
     */
    @Test
    void testTheSecretMessageDecodesToItsPlainText() {
        byte[] decoded = apply(BurrowsWheeler::decode, read("encodedSecretMessage.txt"));

        Assertions.assertEquals(
                "have a good weekend :)", new String(decoded, StandardCharsets.US_ASCII));
    }

    /** The worked example from the assignment, shipped as a file rather than written into a test. */
    @Test
    void testTheAssignmentExampleFile() {
        Assertions.assertEquals("ABRACADABRA!", new String(read("abra.txt"), StandardCharsets.US_ASCII));

        byte[] transformed = read("abra.txt.bwt");

        Assertions.assertArrayEquals(
                new byte[]{0, 0, 0, 3, 'A', 'R', 'D', '!', 'R', 'C', 'A', 'A', 'A', 'A', 'B', 'B'},
                transformed);
    }

    /** A one-byte file, the smallest input any of the stages will see. */
    @Test
    void testTheSingleByteFixture() {
        byte[] original = read("a.txt");

        Assertions.assertEquals(1, original.length);
        Assertions.assertArrayEquals(
                original, apply(BurrowsWheeler::decode, apply(BurrowsWheeler::encode, original)));
        Assertions.assertEquals(0, new CircularSuffixArray("a").index(0));
    }

    // ---------- the suffix array behind the transform ----------

    /**
     * {@link CircularSuffixArray} against a sort written out here. The oracle orders the rotation
     * start positions by comparing characters through a modulo, so it never builds a rotation -
     * which is both the independent check and the space the implementation ought to be using.
     */
    @ParameterizedTest(name = "the circular suffix array of {0} matches a plain sort")
    @ValueSource(strings = {"a.txt", "zebra.txt", "couscous.txt", "abra.txt", "cadabra.txt",
                            "stars.txt", "alphanum.txt"})
    void testTheCircularSuffixArrayMatchesAnIndependentSort(String source) {
        String text = new String(read(source), StandardCharsets.ISO_8859_1);
        CircularSuffixArray suffixes = new CircularSuffixArray(text);

        Assertions.assertEquals(text.length(), suffixes.length(), source);
        int[] expected = sortRotationStarts(text);
        for (int i = 0; i < expected.length; i++) {
            int position = i;
            Assertions.assertEquals(
                    expected[i], suffixes.index(i), () -> source + ": index(" + position + ")");
        }
    }

    /** Every start position appears exactly once, whatever the text. */
    @ParameterizedTest(name = "the circular suffix array of {0} is a permutation")
    @ValueSource(strings = {"abra.txt", "alphanum.txt", "stars.txt", "couscous.txt"})
    void testTheCircularSuffixArrayIsAPermutationOfTheStartPositions(String source) {
        String text = new String(read(source), StandardCharsets.ISO_8859_1);
        CircularSuffixArray suffixes = new CircularSuffixArray(text);

        boolean[] seen = new boolean[text.length()];
        for (int i = 0; i < suffixes.length(); i++) {
            int index = suffixes.index(i);
            Assertions.assertTrue(index >= 0 && index < text.length(), source + ": " + index);
            Assertions.assertFalse(seen[index], source + ": " + index + " appears twice");
            seen[index] = true;
        }
    }

    /** A 192 KB input must not exhaust memory while constructing its circular suffix array. */
    @Test
    void testTheLargestFixtureCanBeEncoded() {
        Assertions.assertDoesNotThrow(
                () -> apply(BurrowsWheeler::encode, read("aesop.txt")),
                "circular suffix construction must use linear space"
        );
    }

    // ---------- helpers ----------

    private interface Stage {
        void run(BinaryIn input, BinaryOut output);
    }

    private static byte[] apply(Stage stage, byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        stage.run(new BinaryIn(new ByteArrayInputStream(input)), new BinaryOut(output));
        return output.toByteArray();
    }

    /** Reads a fixture as raw bytes; several of them are images, not text. */
    private static byte[] read(String fixture) {
        Path directory = ResourceFiles.fixtureDirectory(BurrowsWheeler.class);
        Assertions.assertNotNull(directory, "fixture directory should be found from the code location");
        try {
            return Files.readAllBytes(directory.resolve(fixture));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + fixture, e);
        }
    }

    /**
     * @return the rotation start positions of {@code text}, ordered by the rotation each one
     *         begins, compared character by character without ever building one
     */
    private static int[] sortRotationStarts(String text) {
        int length = text.length();
        Integer[] starts = new Integer[length];
        Arrays.setAll(starts, index -> index);

        Comparator<Integer> byRotation = (left, right) -> {
            for (int offset = 0; offset < length; offset++) {
                char fromLeft = text.charAt((left + offset) % length);
                char fromRight = text.charAt((right + offset) % length);
                if (fromLeft != fromRight) {
                    return Character.compare(fromLeft, fromRight);
                }
            }
            return 0;
        };
        Arrays.sort(starts, byRotation);

        return Stream.of(starts).mapToInt(Integer::intValue).toArray();
    }
}
