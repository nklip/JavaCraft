package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ContainsDuplicateTest {

    @Test
    public void testCase1() {
        int[] nums = {1,2,3,1};

        ContainsDuplicate solution = new ContainsDuplicate();
        Assertions.assertTrue(solution.containsDuplicateUseSort(nums));
        Assertions.assertTrue(solution.containsDuplicateUseSet(nums));
    }

    @Test
    public void testCase2() {
        int[] nums = {1,2,3,4};

        ContainsDuplicate solution = new ContainsDuplicate();
        Assertions.assertFalse(solution.containsDuplicateUseSort(nums));
        Assertions.assertFalse(solution.containsDuplicateUseSet(nums));
    }

    @Test
    public void testCase3() {
        int[] nums = {1,1,1,3,3,4,3,2,4,2};

        ContainsDuplicate solution = new ContainsDuplicate();
        Assertions.assertTrue(solution.containsDuplicateUseSort(nums));
        Assertions.assertTrue(solution.containsDuplicateUseSet(nums));
    }

}
