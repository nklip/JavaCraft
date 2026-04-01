package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RomanToIntegerTest {

    @Test
    void testCases() {
        RomanToInteger solution = new RomanToInteger();
        Assertions.assertEquals(0, solution.romanToInt(""));
        Assertions.assertEquals(1, solution.romanToInt("I"));
        Assertions.assertEquals(3, solution.romanToInt("III"));
        Assertions.assertEquals(5, solution.romanToInt("V"));
        Assertions.assertEquals(10, solution.romanToInt("X"));
        Assertions.assertEquals(25, solution.romanToInt("XXV"));
        Assertions.assertEquals(49, solution.romanToInt("XLIX"));
        Assertions.assertEquals(50, solution.romanToInt("L"));
        Assertions.assertEquals(58, solution.romanToInt("LVIII"));
        Assertions.assertEquals(100, solution.romanToInt("C"));
        Assertions.assertEquals(500, solution.romanToInt("D"));
        Assertions.assertEquals(542, solution.romanToInt("DXLII"));
        Assertions.assertEquals(999, solution.romanToInt("CMXCIX"));
        Assertions.assertEquals(1000, solution.romanToInt("M"));
        Assertions.assertEquals(1023, solution.romanToInt("MXXIII"));
        Assertions.assertEquals(1994, solution.romanToInt("MCMXCIV"));
        Assertions.assertEquals(3999, solution.romanToInt("MMMCMXCIX"));
        Assertions.assertEquals(4999, solution.romanToInt("MMMMCMXCIX"));
    }

    @Test
    void testExceptions() {
        RomanToInteger solution = new RomanToInteger();
        try {
            solution.romanToInt(null);
        } catch (IllegalArgumentException iae) {
            Assertions.assertEquals("Roman number cannot be null", iae.getMessage());
        }
        try {
            solution.romanToInt("IIII");
        } catch (IllegalArgumentException iae) {
            Assertions.assertEquals("Roman number is invalid", iae.getMessage());
        }
        try {
            solution.romanToInt("IM");
        } catch (IllegalArgumentException iae) {
            Assertions.assertEquals("Roman number is invalid", iae.getMessage());
        }
        try {
            solution.romanToInt("ABC");
        } catch (IllegalArgumentException iae) {
            Assertions.assertEquals("Roman number is invalid", iae.getMessage());
        }
    }


}
