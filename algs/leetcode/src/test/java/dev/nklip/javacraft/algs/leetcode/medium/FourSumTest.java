package dev.nklip.javacraft.algs.leetcode.medium;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class FourSumTest {
    @Test
    void testCase1() {
        FourSum solution = new FourSum();

        List<List<Integer>> result = solution.fourSum(new int[]{1,0,-1,0,-2,2}, 0);
        Assertions.assertEquals(3, result.size());
        Assertions.assertEquals("[-2, -1, 1, 2]", result.getFirst().toString());
        Assertions.assertEquals("[-2, 0, 0, 2]", result.get(1).toString());
        Assertions.assertEquals("[-1, 0, 0, 1]", result.getLast().toString());
    }

    @Test
    void testCase2() {
        FourSum solution = new FourSum();

        List<List<Integer>> result = solution.fourSum(new int[]{2,2,2,2,2}, 8);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("[2, 2, 2, 2]", result.getFirst().toString());
    }

    @Test
    void testCase3() {
        FourSum solution = new FourSum();

        List<List<Integer>> result = solution.fourSum(new int[]{-3,-1,0,2,4,5}, 0);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("[-3, -1, 0, 4]", result.getFirst().toString());
    }

    @Test
    void testCase4() {
        FourSum solution = new FourSum();

        List<List<Integer>> result = solution.fourSum(new int[]{-3,-1,0,2,4,5}, 2);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("[-3, -1, 2, 4]", result.getFirst().toString());
    }

    @Test
    void testCase5() {
        FourSum solution = new FourSum();

        List<List<Integer>> result = solution.fourSum(new int[]{-10,0,1,2,3,5}, -4);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("[-10, 0, 1, 5]", result.getFirst().toString());
        Assertions.assertEquals("[-10, 1, 2, 3]", result.getLast().toString());
    }
}
