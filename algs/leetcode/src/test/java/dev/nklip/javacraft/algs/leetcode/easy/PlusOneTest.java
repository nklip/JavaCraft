package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PlusOneTest {

    @Test
    void testCase1() {
        PlusOne solution = new PlusOne();

        int [] digits = {1,2,3};
        int [] expectedResult = {1,2,4};

        // Explanation: The array represents the integer 123.
        // Incrementing by one gives 123 + 1 = 124.
        // Thus, the result should be [1,2,4].
        Assertions.assertArrayEquals(expectedResult, solution.plusOne(digits));
    }

    @Test
    void testCase2() {
        PlusOne solution = new PlusOne();

        int [] digits = {4,3,2,1};
        int [] expectedResult = {4,3,2,2};

        // Explanation: The array represents the integer 4321.
        // Incrementing by one gives 4321 + 1 = 4322.
        // Thus, the result should be [4,3,2,2].
        Assertions.assertArrayEquals(expectedResult, solution.plusOne(digits));
    }

    @Test
    void testCase3() {
        PlusOne solution = new PlusOne();

        int [] digits = {9};
        int [] expectedResult = {1,0};

        // Explanation: The array represents the integer 9.
        // Incrementing by one gives 9 + 1 = 10.
        // Thus, the result should be [1,0].
        Assertions.assertArrayEquals(expectedResult, solution.plusOne(digits));
    }
}
