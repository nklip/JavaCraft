package dev.nklip.javacraft.algs.leetcode.easy;

/*
 * 190. Reverse Bits
 * 
 * Reverse bits of a given 32 bits signed integer.
 * 
 * Example 1:
 * 
 * Input: n = 43261596
 * 
 * Output: 964176192
 * 
 * Explanation:
 *
 * ┌------------┬----------------------------------┐
 * | Integer    | Binary                           |
 * |------------┼----------------------------------|
 * | 43261596   | 00000010100101000001111010011100 |
 * | 964176192  | 00111001011110000010100101000000 |
 * └------------┴----------------------------------┘
 *
 * Example 2:
 * 
 * Input: n = 2147483644
 * 
 * Output: 1073741822
 * 
 * Explanation:
 * ┌------------┬----------------------------------┐
 * | Integer    | Binary                           |
 * |------------┼----------------------------------|
 * | 2147483644 | 01111111111111111111111111111100 |
 * | 1073741822 | 00111111111111111111111111111110 |
 * └------------┴----------------------------------┘
 *
 */
public class ReverseBits {

    /**
     * It means: take the 32-bit binary representation of the integer and reverse the bit order (leftmost bit becomes rightmost, etc.).
     * <p>
     * It is not reversing decimal digits, and not “negating” sign.
     * <p>
     * You just reverse raw bits.
     * <p>
     * Algorithm (32 fixed steps):
     * <p>
     * 1) Start result = 0.
     * 2) Repeat 32 times:
     *      2.1) Shift result left by 1.
     *      2.2) Copy lowest bit of n into result (result |= n & 1).
     *      2.3) Shift n right by 1 (>>> in Java).
     * 3) Return result.
     */
    public int reverseBits(int n) {
        int result = 0;
        for (int i = 0; i < 32; i++) {
            // shift all bits in result one place to the left
            // this makes room on the right to add the next bit
            // so
            // 1) each bit moves left by 1
            // 2) a 0 is added on the right
            // 3) numeric value roughly doubles
            result = result << 1;
            // result | thatBit - puts that bit into rightmost position of result
            // |                - is bitwise OR here
            // n & 1            - gets the last bit of n (either 0 or 1)
            // as we appended 0 to the rightmost position in result
            // so result is always 0 for this position
            // Then result = result | (n & 1) writes the extracted bit there:
            // if (n & 1) == 0, rightmost stays 0
            // if (n & 1) == 1, rightmost becomes 1
            result = result | (n & 1);
            // unsigned shift:
            // 1) All bits move right by 1.
            // 2) The rightmost bit is dropped (that’s the one already was read via n & 1).
            // 3) A 0 is inserted on the leftmost side.
            //
            // It consumes bits from right to left, one by one.
            //
            // >>> is important because it always inserts 0 on the left (unlike >>, which copies sign bit).
            n = n >>> 1;
        }
        return result;
    }
}
