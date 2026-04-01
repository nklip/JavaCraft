package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SingleNumberTest {

    @Test
    void testCase1() {
        int[] nums = {2,2,1};

        SingleNumber solution = new SingleNumber();
        Assertions.assertEquals(1, solution.singleNumber(nums));
        Assertions.assertEquals(1, solution.singleNumber2(nums));
    }

    @Test
    void testCase2() {
        int[] nums = {4,1,2,1,2};

        SingleNumber solution = new SingleNumber();
        Assertions.assertEquals(4, solution.singleNumber(nums));
        Assertions.assertEquals(4, solution.singleNumber2(nums));
    }

    @Test
    void testCase3() {
        int[] nums = {1};

        SingleNumber solution = new SingleNumber();
        Assertions.assertEquals(1, solution.singleNumber(nums));
        Assertions.assertEquals(1, solution.singleNumber2(nums));
    }
}
