package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NumberOf1BitsTest {

    @Test
    void testCase1() {
        NumberOf1Bits solution = new NumberOf1Bits();

        // The input binary string 1011 has a total of three set bits.
        Assertions.assertEquals(3, solution.hammingWeight(11));
    }

    @Test
    void testCase2() {
        NumberOf1Bits solution = new NumberOf1Bits();

        // The input binary string 10000000 has a total of one set bit.
        Assertions.assertEquals(1, solution.hammingWeight(128));
    }

    @Test
    void testCase3() {
        NumberOf1Bits solution = new NumberOf1Bits();

        // The input binary string 1111111111111111111111111111101 has a total of thirty set bits.
        Assertions.assertEquals(30, solution.hammingWeight(2147483645));
    }
}
