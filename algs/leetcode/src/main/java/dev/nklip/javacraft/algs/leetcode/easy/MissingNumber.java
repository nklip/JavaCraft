package dev.nklip.javacraft.algs.leetcode.easy;

/*
 * 268. Missing Number
 *
 * LeetCode: https://leetcode.com/problems/missing-number/
 *
 * Given an array nums containing n distinct numbers in the range [0, n],
 * return the only number in the range that is missing from the array.
 */
public class MissingNumber {
    public int missingNumber(int[] nums) {
        int i = 0;
        int length = nums.length;

        while (i < length) {
            int value = nums[i];
            if (value < length && value != nums[value]) {
                // swap
                int temp = nums[value];
                nums[value] = value;
                nums[i] = temp;
            } else {
                i++;
            }
        }

        // find first missing number
        for (int k = 0; k < nums.length; k++) {
            if (nums[k] != k) {
                return k;
            }
        }
        return nums.length;
    }
}
