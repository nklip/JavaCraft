import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

class MoveToFrontTest {

    @Test
    void testEncodesAssignmentSample() {
        byte[] encoded = encode("ABRACADABRA!".getBytes(StandardCharsets.US_ASCII));

        Assertions.assertArrayEquals(
                new byte[]{65, 66, 82, 2, 68, 1, 69, 1, 4, 4, 2, 38},
                encoded
        );
    }

    @Test
    void testRoundTripsBinaryDataAndEmptyInput() {
        byte[] binary = {0, -1, 0, 127, -128, 1};

        Assertions.assertArrayEquals(binary, decode(encode(binary)));
        Assertions.assertArrayEquals(new byte[0], decode(encode(new byte[0])));
    }

    /** {@code -} encodes and {@code +} decodes, reading standard input and writing standard output. */
    @Test
    void testMainEncodesWithMinusAndDecodesWithPlus() {
        byte[] text = "ABRACADABRA!".getBytes(StandardCharsets.US_ASCII);

        byte[] encoded = BurrowsWheelerTest.throughProcess("MoveToFront", text, "-");

        Assertions.assertArrayEquals(encode(text), encoded);
        Assertions.assertArrayEquals(
                text, BurrowsWheelerTest.throughProcess("MoveToFront", encoded, "+"));
    }

    @Test
    void testTheStandardStreamEntryPointsEncodeAndDecode() {
        byte[] text = "BANANA".getBytes(StandardCharsets.US_ASCII);

        byte[] encoded = BurrowsWheelerTest.throughProcess("MoveToFront", text, "-");

        Assertions.assertArrayEquals(encode(text), encoded);
        Assertions.assertArrayEquals(
                text,
                BurrowsWheelerTest.throughProcess("MoveToFront", encoded, "+"));
    }

    @Test
    void testMainReportsWhenGivenNoRecognisableArgument() {
        Assertions.assertEquals(
                "Nothing is found!",
                new String(BurrowsWheelerTest.throughStandardStreams(MoveToFront::main, new byte[0]),
                        StandardCharsets.US_ASCII).trim());
        Assertions.assertEquals(
                "Nothing is found!",
                new String(BurrowsWheelerTest.throughStandardStreams(
                        MoveToFront::main, new byte[0], (String[]) null),
                        StandardCharsets.US_ASCII).trim());
        Assertions.assertEquals(
                0, BurrowsWheelerTest.throughStandardStreams(MoveToFront::main, new byte[0], "?").length);
    }

    private static byte[] encode(byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MoveToFront.encode(
                new BinaryIn(new ByteArrayInputStream(input)),
                new BinaryOut(output)
        );
        return output.toByteArray();
    }

    private static byte[] decode(byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MoveToFront.decode(
                new BinaryIn(new ByteArrayInputStream(input)),
                new BinaryOut(output)
        );
        return output.toByteArray();
    }
}
