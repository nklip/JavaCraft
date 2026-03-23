package my.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TwoSumTest {

    @Test
    void testCase1() {
        TwoSum solution = new TwoSum();

        int []nums = {2,7,11,15};
        int target = 9;

        int []expectedResult = {0,1};
        int []actualResult = solution.twoSum(nums, target);
        Assertions.assertEquals(expectedResult[0], actualResult[0]);
        Assertions.assertEquals(expectedResult[1], actualResult[1]);
    }

    @Test
    void testCase2() {
        TwoSum solution = new TwoSum();

        int []nums = {3,2,4};
        int target = 6;

        int []expectedResult = {1,2};
        int []actualResult = solution.twoSum(nums, target);
        Assertions.assertEquals(expectedResult[0], actualResult[0]);
        Assertions.assertEquals(expectedResult[1], actualResult[1]);
    }

    @Test
    void testCase3() {
        TwoSum solution = new TwoSum();

        int []nums = {3,3};
        int target = 6;

        int []expectedResult = {0,1};
        int []actualResult = solution.twoSum(nums, target);
        Assertions.assertEquals(expectedResult[0], actualResult[0]);
        Assertions.assertEquals(expectedResult[1], actualResult[1]);
    }

    @Test
    void testCase4() {
        TwoSum solution = new TwoSum();

        int []nums = {0,4,3,0};
        int target = 0;

        int []expectedResult = {0,3};
        int []actualResult = solution.twoSum(nums, target);
        Assertions.assertEquals(expectedResult[0], actualResult[0]);
        Assertions.assertEquals(expectedResult[1], actualResult[1]);
    }

    @Test
    void testCase5() {
        TwoSum solution = new TwoSum();

        int []nums = {-3,4,3,90};
        int target = 0;

        int []expectedResult = {0,2};
        int []actualResult = solution.twoSum(nums, target);
        Assertions.assertEquals(expectedResult[0], actualResult[0]);
        Assertions.assertEquals(expectedResult[1], actualResult[1]);
    }

    @Test
    void testCase6() {
        TwoSum solution = new TwoSum();

        int []nums = {-3,4,3,90};
        int target = 0;

        int []expectedResult = {0,2};
        int []actualResult = solution.twoSum2(nums, target);
        Assertions.assertEquals(expectedResult[0], actualResult[0]);
        Assertions.assertEquals(expectedResult[1], actualResult[1]);
    }
}
