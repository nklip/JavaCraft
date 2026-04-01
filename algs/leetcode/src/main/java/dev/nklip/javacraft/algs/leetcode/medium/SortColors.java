package dev.nklip.javacraft.algs.leetcode.medium;

/*
 * 75. Sort Colors
 *
 * LeetCode: https://leetcode.com/problems/sort-colors/
 *
 * Given an array nums with n objects colored red, white, or blue,
 * sort them in-place so that objects of the same color are adjacent,
 * with the colors in the order red, white, and blue.
 *
 * We will use the integers 0, 1, and 2 to represent the color red, white, and blue, respectively.
 *
 * You must solve this problem without using the library's sort function.
 */
public class SortColors {

    public void sortColors(final int[] nums) {
        int red = 0;
        int white = 0;
        for (int num : nums) {
            if (num == 0) {
                red++;
            } else if (num == 1) {
                white++;
            }
        }
        for (int i = 0; i < nums.length; i++) {
            if (red-- > 0) {
                nums[i] = 0;
            } else if (white-- > 0) {
                nums[i] = 1;
            } else {
                nums[i] = 2;
            }
        }
    }

}
