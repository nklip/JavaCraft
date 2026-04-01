package dev.nklip.javacraft.algs.leetcode.medium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ZigZagConversionTest {

    @Test
    void testCase1() {
        // P   A   H   N
        // A P L S I I G
        // Y   I   R
        ZigZagConversion solution = new ZigZagConversion();
        Assertions.assertEquals("PAHNAPLSIIGYIR", solution.convert("PAYPALISHIRING", 3));
    }

    @Test
    void testCase2() {
        // Input: s = "PAYPALISHIRING", numRows = 4
        // Output: "PINALSIGYAHRPI"
        // Explanation:
        // P     I    N
        // A   L S  I G
        // Y A   H R
        // P     I
        ZigZagConversion solution = new ZigZagConversion();
        Assertions.assertEquals("PINALSIGYAHRPI", solution.convert("PAYPALISHIRING", 4));
    }

    @Test
    void testCase3() {
        ZigZagConversion solution = new ZigZagConversion();
        Assertions.assertEquals("A", solution.convert("A", 1));
    }

    @Test
    void testCase4() {
        ZigZagConversion solution = new ZigZagConversion();
        Assertions.assertEquals("AB", solution.convert("AB", 1));
    }
}
