package my.javacraft.algs.leetcode.medium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ThreeSumTest {

    @Test
    void testCase1() {
        int[] nums = {-1,0,1,2,-1,-4};

        ThreeSum solution = new ThreeSum();
        // Explanation:
        // nums[0] + nums[1] + nums[2] = (-1) + 0 + 1 = 0.
        // nums[0] + nums[3] + nums[4] = (-1) + 2 + (-1) = 0.
        // nums[1] + nums[2] + nums[4] = 0 + 1 + (-1) = 0.
        // The distinct triplets are [-1,0,1] and [-1,-1,2].
        // Notice that the order of the output and the order of the triplets does not matter.
        Assertions.assertEquals("[[-1, -1, 2], [-1, 0, 1]]", solution.threeSum(nums).toString());
        Assertions.assertEquals("[[-1, -1, 2], [-1, 0, 1]]", solution.threeSum2(nums).toString());
    }

    @Test
    void testCase2() {
        int[] nums = {0,1,1};

        ThreeSum solution = new ThreeSum();
        // Explanation: The only possible triplet does not sum up to 0.
        Assertions.assertEquals("[]", solution.threeSum(nums).toString());
        Assertions.assertEquals("[]", solution.threeSum2(nums).toString());
    }

    @Test
    void testCase3() {
        int[] nums = {0,0,0};

        ThreeSum solution = new ThreeSum();
        // Explanation: The only possible triplet sums up to 0.
        Assertions.assertEquals("[[0, 0, 0]]", solution.threeSum(nums).toString());
        Assertions.assertEquals("[[0, 0, 0]]", solution.threeSum2(nums).toString());
    }

}
