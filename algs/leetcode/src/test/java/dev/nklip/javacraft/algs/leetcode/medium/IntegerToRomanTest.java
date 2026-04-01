package dev.nklip.javacraft.algs.leetcode.medium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class IntegerToRomanTest {

    @Test
    void testCases() {
        IntegerToRoman solution = new IntegerToRoman();
        Assertions.assertEquals("", solution.intToRoman(0));
        Assertions.assertEquals("I", solution.intToRoman(1));
        Assertions.assertEquals("V", solution.intToRoman(5));
        Assertions.assertEquals("X", solution.intToRoman(10));
        Assertions.assertEquals("XXV", solution.intToRoman(25));
        Assertions.assertEquals("XLIX", solution.intToRoman(49));
        Assertions.assertEquals("L", solution.intToRoman(50));
        Assertions.assertEquals("LVIII", solution.intToRoman(58));
        Assertions.assertEquals("C", solution.intToRoman(100));
        Assertions.assertEquals("D", solution.intToRoman(500));
        Assertions.assertEquals("DXLII", solution.intToRoman(542));
        Assertions.assertEquals("CMXCIX", solution.intToRoman(999));
        Assertions.assertEquals("M", solution.intToRoman(1000));
        Assertions.assertEquals("MXXIII", solution.intToRoman(1023));
        Assertions.assertEquals("MCMXCIV", solution.intToRoman(1994));
        Assertions.assertEquals("MMMDCCXLIX", solution.intToRoman(3749));
        Assertions.assertEquals("MMMCMXCIX", solution.intToRoman(3999));
        Assertions.assertEquals("MMMMCMXCIX", solution.intToRoman(4999));
    }

    @Test
    void testExceptions() {
        IntegerToRoman solution = new IntegerToRoman();
        try {
            solution.intToRoman(-1);
        } catch (IllegalArgumentException iae) {
            Assertions.assertEquals("Number is out of supported range: -1", iae.getMessage());
        }
        try {
            solution.intToRoman(5000);
        } catch (IllegalArgumentException iae) {
            Assertions.assertEquals("Number is out of supported range: 5000", iae.getMessage());
        }
    }

}
