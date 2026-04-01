package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SqrtTest {

    @Test
    void testCase1() {
        Sqrt solution = new Sqrt();

        // Explanation: The square root of 4 is 2, so we return 2.
        Assertions.assertEquals(2, solution.mySqrt(4));
    }

    @Test
    void testCase2() {
        Sqrt solution = new Sqrt();

        // Explanation: The square root of 8 is 2.82842...,
        // and since we round it down to the nearest integer, 2 is returned.
        Assertions.assertEquals(2, solution.mySqrt(8));
    }

    @Test
    void testCase3() {
        Sqrt solution = new Sqrt();

        Assertions.assertEquals(1831, solution.mySqrt(3355518));
    }

}
