import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

class BurrowsWheelerTest {

    @Test
    void testEncodesAssignmentSample() {
        byte[] encoded = encode("ABRACADABRA!".getBytes(StandardCharsets.US_ASCII));

        Assertions.assertArrayEquals(
                new byte[]{
                        0, 0, 0, 3,
                        'A', 'R', 'D', '!', 'R', 'C', 'A', 'A', 'A', 'A', 'B', 'B'
                },
                encoded
        );
    }

    @Test
    void testRoundTripsTextAndEmptyInput() {
        byte[] text = "BANANA_BANDANA".getBytes(StandardCharsets.US_ASCII);

        Assertions.assertArrayEquals(text, decode(encode(text)));
        Assertions.assertArrayEquals(new byte[0], decode(encode(new byte[0])));
    }

    @Test
    void testRejectsInvalidFirstIndex() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> decode(encoded(1)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> decode(encoded(2, 'A')));
        Assertions.assertThrows(IllegalArgumentException.class, () -> decode(encoded(-1, 'A')));
    }

    /**
     * Every nonzero byte occurs only near the end, so repeatedly scanning the transformed input
     * to locate equal bytes takes approximately {@code R * N} comparisons.
     * Key-indexed counting builds both the sorted column
     * and {@code next[]} in time proportional to {@code N + R}.
     */
    @Test
    void testDecodesAllByteValuesInLinearTime() {
        int transformedLength = 8_000_000;
        byte[] encoded = new byte[Integer.BYTES + transformedLength];
        for (int value = 0; value < 256; value++) {
            encoded[encoded.length - 256 + value] = (byte) value;
        }

        Assertions.assertTimeout(Duration.ofSeconds(3), () -> {
            for (int repetition = 0; repetition < 8; repetition++) {
                Assertions.assertEquals(transformedLength, decode(encoded).length);
            }
        });
    }

    /**
     * {@code BinaryStdIn} and {@code BinaryStdOut} bind to the process streams once, so each public
     * standard-I/O entry point is verified in a fresh JVM.
     */
    @Test
    void testTheStandardStreamEntryPointsTransformStdinToStdout() {
        byte[] transformed = throughProcess(
                "BurrowsWheeler", "ABRACADABRA!".getBytes(StandardCharsets.US_ASCII), "-");

        Assertions.assertArrayEquals(
                new byte[]{0, 0, 0, 3, 'A', 'R', 'D', '!', 'R', 'C', 'A', 'A', 'A', 'A', 'B', 'B'},
                transformed);
        Assertions.assertArrayEquals(
                "ABRACADABRA!".getBytes(StandardCharsets.US_ASCII),
                throughProcess("BurrowsWheeler", transformed, "+"));
    }

    /** {@code -} encodes and {@code +} decodes; the transform is the same either way. */
    @Test
    void testMainEncodesWithMinusAndDecodesWithPlus() {
        byte[] text = "BANANA_BANDANA".getBytes(StandardCharsets.US_ASCII);

        byte[] transformed = throughProcess("BurrowsWheeler", text, "-");

        Assertions.assertArrayEquals(encode(text), transformed);
        Assertions.assertArrayEquals(
                text, throughProcess("BurrowsWheeler", transformed, "+"));
    }

    @Test
    void testMainReportsWhenGivenNoRecognisableArgument() {
        byte[] ignored = new byte[0];

        Assertions.assertEquals(
                "Nothing is found!",
                new String(throughStandardStreams(BurrowsWheeler::main, ignored),
                        StandardCharsets.US_ASCII).trim());
        Assertions.assertEquals(
                "Nothing is found!",
                new String(throughStandardStreams(BurrowsWheeler::main, ignored, (String[]) null),
                        StandardCharsets.US_ASCII).trim());
        Assertions.assertEquals(
                0,
                throughStandardStreams(BurrowsWheeler::main, ignored, "?").length,
                "an unrecognised flag does nothing at all");
    }

    /** Runs something that reads {@code System.in} and writes {@code System.out}, and captures it. */
    static byte[] throughStandardStreams(Consumer<String[]> entryPoint, byte[] input, String... args) {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(input));
            System.setOut(new PrintStream(captured, true, StandardCharsets.ISO_8859_1));
            entryPoint.accept(args);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        return captured.toByteArray();
    }

    /** Runs a command-line entry point whose binary standard streams are process-global. */
    static byte[] throughProcess(String className, byte[] input, String... args) {
        String[] command = new String[args.length + 4];
        command[0] = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        command[1] = "-cp";
        command[2] = System.getProperty("java.class.path");
        command[3] = className;
        System.arraycopy(args, 0, command, 4, args.length);

        try {
            Process process = new ProcessBuilder(command).start();
            try (OutputStream standardInput = process.getOutputStream()) {
                standardInput.write(input);
            }

            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                Assertions.assertSame(process, process.destroyForcibly());
                Assertions.fail(className + " did not finish within 10 seconds");
            }

            byte[] output = process.getInputStream().readAllBytes();
            byte[] error = process.getErrorStream().readAllBytes();
            Assertions.assertEquals(
                    0,
                    process.exitValue(),
                    () -> new String(error, StandardCharsets.UTF_8)
            );
            return output;
        } catch (IOException e) {
            throw new AssertionError("Could not run " + className, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while running " + className, e);
        }
    }

    private static byte[] encode(byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BurrowsWheeler.encode(
                new BinaryIn(new ByteArrayInputStream(input)),
                new BinaryOut(output)
        );
        return output.toByteArray();
    }

    private static byte[] decode(byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BurrowsWheeler.decode(
                new BinaryIn(new ByteArrayInputStream(input)),
                new BinaryOut(output)
        );
        return output.toByteArray();
    }

    private static byte[] encoded(int first, char... transformed) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryOut binaryOut = new BinaryOut(output);
        binaryOut.write(first);
        for (char value : transformed) {
            binaryOut.write(value, 8);
        }
        binaryOut.close();
        return output.toByteArray();
    }
}
