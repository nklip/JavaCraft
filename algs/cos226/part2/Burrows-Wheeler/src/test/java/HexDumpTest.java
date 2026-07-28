import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

class HexDumpTest {

    @Test
    void testFormatsBytesAndBitCount() {
        BinaryIn input = new BinaryIn(new ByteArrayInputStream(new byte[]{0, 16, -1}));

        Assertions.assertEquals("00 10\nff\n24 bits\n", HexDump.dump(input, 2));
    }

    @Test
    void testCanPrintOnlyBitCount() {
        BinaryIn input = new BinaryIn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        Assertions.assertEquals("24 bits\n", HexDump.dump(input, 0));
    }

    /**
     * {@code main} prints through {@code StdOut}, which caches {@code System.out} when its class
     * is initialized, so only that it runs is asserted here. The formatting itself is checked
     * above through {@code dump}.
     */
    @Test
    void testMainRunsWithAndWithoutAByteCountArgument() {
        byte[] input = "ABRACADABRA!".getBytes(StandardCharsets.US_ASCII);

        Assertions.assertDoesNotThrow(() ->
                BurrowsWheelerTest.throughStandardStreams(HexDump::main, input, "4"));
        Assertions.assertDoesNotThrow(() ->
                BurrowsWheelerTest.throughStandardStreams(HexDump::main, input));
    }
}
