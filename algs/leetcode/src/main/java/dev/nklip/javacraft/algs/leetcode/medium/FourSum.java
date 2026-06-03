package dev.nklip.javacraft.algs.leetcode.medium;

import java.util.*;

/*
 * 18. 4Sum
 *
 * LeetCode: https://leetcode.com/problems/4sum/
 *
 * Given an array nums of n integers, return an array of all the unique quadruplets [nums[a], nums[b], nums[c], nums[d]] such that:
 *
 * 1) 0 <= a, b, c, d < n
 * 2) a, b, c, and d are distinct.
 * 3) nums[a] + nums[b] + nums[c] + nums[d] == target
 *
 * You may return the answer in any order.
 */
public class FourSum {
    // Time Complexity - O(n^3)
    public List<List<Integer>> fourSum(int[] nums, int target) {
        Arrays.sort(nums);

        List<List<Integer>> result = new ArrayList<>();

        for (int i = 0; i < nums.length - 3; i++) {
            // skip duplicates
            if (i > 0 && nums[i] == nums[i - 1]) {
                continue;
            }
            for (int j = i + 1; j < nums.length - 2; j++) {
                // skip duplicates
                if (j > i + 1 && nums[j] == nums[j - 1]) {
                    continue;
                }

                long tempTarget = (long) target - nums[i] - nums[j];
                int left = j + 1;
                int right = nums.length - 1;

                while (left < right) {
                    long sum = (long) nums[left] + nums[right];

                    if (sum == tempTarget) {
                        result.add(List.of(nums[i], nums[j], nums[left], nums[right]));
                        // skip duplicates
                        while (left < right && nums[left] == nums[left + 1]) {
                            left++;
                        }
                        // skip duplicates
                        while (left < right && nums[right] == nums[right - 1]) {
                            right--;
                        }
                        right--;
                        left++;
                    } else if (sum > tempTarget) {
                        right--;
                    } else {
                        left++;
                    }
                }
            }
        }
        return result;
    }
}
