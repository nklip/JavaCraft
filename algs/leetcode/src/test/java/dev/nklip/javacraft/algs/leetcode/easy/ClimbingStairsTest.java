package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ClimbingStairsTest {

    @Test
    void testCase1() {
        ClimbingStairs solution = new ClimbingStairs();

        // Explanation: There are two ways to climb to the top.
        // 1. 1 step + 1 step
        // 2. 2 steps
        Assertions.assertEquals(2, solution.climbStairs(2));
        Assertions.assertEquals(2, solution.climbStairs2(2));
    }

    @Test
    void testCase2() {
        ClimbingStairs solution = new ClimbingStairs();

        // Explanation: There are three ways to climb to the top.
        // 1. 1 step + 1 step + 1 step
        // 2. 1 step + 2 steps
        // 3. 2 steps + 1 step
        Assertions.assertEquals(3, solution.climbStairs(3));
        Assertions.assertEquals(3, solution.climbStairs2(3));
    }

    @Test
    void testCase3() {
        ClimbingStairs solution = new ClimbingStairs();

        // Explanation: There are three ways to climb to the top.
        // 1. 1 step + 1 step + 1 step
        // 2. 1 step + 2 steps
        // 3. 2 steps + 1 step
        Assertions.assertEquals(1836311903, solution.climbStairs(45));
        Assertions.assertEquals(1836311903, solution.climbStairs2(45));
    }
}
