package dev.nklip.javacraft.algs.leetcode.medium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ContainerWithMostWaterTest {

    @Test
    void testCase1() {
        int []heights = {1,8,6,2,5,4,8,3,7};
        ContainerWithMostWater solution = new ContainerWithMostWater();

        Assertions.assertEquals(49, solution.maxArea(heights));
    }

    @Test
    void testCase2() {
        int []heights = {1,1};
        ContainerWithMostWater solution = new ContainerWithMostWater();

        Assertions.assertEquals(1, solution.maxArea(heights));
    }
}
