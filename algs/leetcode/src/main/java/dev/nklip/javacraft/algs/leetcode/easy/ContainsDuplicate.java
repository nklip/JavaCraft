package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/*
 * 217. Contains Duplicate
 *
 * LeetCode: https://leetcode.com/problems/contains-duplicate/
 *
 * Given an integer array nums, return true if any value appears at least twice in the array,
 * and return false if every element is distinct.
 */
public class ContainsDuplicate {

    // uses less memory: beats 13.07% in runtime and beats 87.18% in memory
    // Time  complexity: O(n log(n))
    // Space complexity: O(log(n))
    public boolean containsDuplicateUseSort(int[] nums) {
        Arrays.sort(nums);

        for (int i = 1; i < nums.length; i++) {
            int prev = nums[i - 1];
            int curr = nums[i];

            if (prev == curr) {
                return true;
            }
        }
        return false;
    }

    // faster: beats 88.85% in runtime and beats 34.63% in memory
    // Time  complexity: O(n)
    // Space complexity: O(n)
    public boolean containsDuplicateUseSet(int[] nums) {
        Set<Integer> set = new HashSet<>();

        for (int num : nums) {
            if (set.contains(num)) {
                return true;
            }
            set.add(num);
        }
        return false;
    }

}
