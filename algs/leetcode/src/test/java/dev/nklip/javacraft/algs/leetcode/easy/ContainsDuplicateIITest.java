package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ContainsDuplicateIITest {

    @Test
    void testCase1() {
        int[] nums = new int[] {1, 2, 3, 1};
        ContainsDuplicateII solution = new ContainsDuplicateII();

        // nums[0] == nums[3] and abs(3-0) <= 3
        Assertions.assertTrue(solution.containsNearbyDuplicate(nums, 3));
        Assertions.assertTrue(solution.containsNearbyDuplicateUseHashMap(nums, 3));
    }

    @Test
    void testCase2() {
        int[] nums = new int[] {1, 0, 1, 1};
        ContainsDuplicateII solution = new ContainsDuplicateII();

        // nums[2] == nums[3] and abs(3-2) <= 1
        Assertions.assertTrue(solution.containsNearbyDuplicate(nums, 1));
        Assertions.assertTrue(solution.containsNearbyDuplicateUseHashMap(nums, 1));
    }

    @Test
    void testCase3() {
        int[] nums = new int[] {1, 2, 3, 1, 2, 3};
        ContainsDuplicateII solution = new ContainsDuplicateII();

        // nums[0] == nums[3] and abs(3-0) >= 2
        Assertions.assertFalse(solution.containsNearbyDuplicate(nums, 2));
        Assertions.assertFalse(solution.containsNearbyDuplicateUseHashMap(nums, 2));
    }

    @Test
    void testCase4() {
        int[] nums = new int[] {2, 2};
        ContainsDuplicateII solution = new ContainsDuplicateII();

        // nums[0] == nums[3] and abs(3-0) >= 2
        Assertions.assertTrue(solution.containsNearbyDuplicate(nums, 3));
        Assertions.assertTrue(solution.containsNearbyDuplicateUseHashMap(nums, 3));
    }


}
