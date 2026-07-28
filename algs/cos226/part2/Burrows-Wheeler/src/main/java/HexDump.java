/*************************************************************************
 *  Compilation:  javac HexDump.java
 *  Execution:    java HexDump < file
 *  Dependencies: BinaryStdIn.java StdOut.java
 *  Data file:    http://algs4.cs.princeton.edu/55compression/abra.txt
 * <p>
 *  Reads in a binary file and writes out the bytes in hex, 16 per line.
 * <p>
 *  % more abra.txt
 *  ABRACADABRA!
 * <p>
 *  % java HexDump 16 < abra.txt
 *  41 42 52 41 43 41 44 41 42 52 41 21
 *  96 bits
 * <p>
 *
 *  Remark
 *  --------------------------
 *   - Similar to the Unix utilities od (octal dump) or hexdump (hexadecimal dump).
 * <p>
 *  % od -t x1 < abra.txt
 *  0000000 41 42 52 41 43 41 44 41 42 52 41 21
 *  0000014
 *
 *************************************************************************/
public class HexDump {

    static void main(String[] args) {
        int bytesPerLine = 16;
        if (args.length == 1) {
            bytesPerLine = Integer.parseInt(args[0]);
        }
        StdOut.print(dump(new BinaryIn(System.in), bytesPerLine));
    }

    static String dump(BinaryIn binaryIn, int bytesPerLine) {
        StringBuilder result = new StringBuilder();
        int byteCount = 0;
        while (!binaryIn.isEmpty()) {
            char value = binaryIn.readChar();
            if (bytesPerLine != 0) {
                if (byteCount > 0) {
                    result.append(byteCount % bytesPerLine == 0 ? '\n' : ' ');
                }
                result.append(String.format("%02x", value & 0xff));
            }
            byteCount++;
        }
        if (bytesPerLine != 0) {
            result.append('\n');
        }
        return result.append(byteCount * 8).append(" bits\n").toString();
    }
}
