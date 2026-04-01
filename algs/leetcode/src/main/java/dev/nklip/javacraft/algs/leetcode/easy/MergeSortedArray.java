package dev.nklip.javacraft.algs.leetcode.easy;

/*
 * 88. Merge Sorted Array
 *
 * LeetCode: https://leetcode.com/problems/merge-sorted-array/
 *
 * You are given two integer arrays nums1 and nums2, sorted in non-decreasing order, and two integers m and n,
 * representing the number of elements in nums1 and nums2 respectively.
 *
 * Merge nums1 and nums2 into a single array sorted in non-decreasing order.
 *
 * The final sorted array should not be returned by the function, but instead be stored inside the array nums1.
 * To accommodate this, nums1 has a length of m + n, where the first m elements denote the elements that should be merged,
 * and the last n elements are set to 0 and should be ignored. nums2 has a length of n.
 */
public class MergeSortedArray {
    public void merge(int[] nums1, int m, int[] nums2, int n) {
        int y = 0;
        for (int i = 0; i < nums1.length; i++) {
            // swap numbers
            if (nums2.length > 0 && i < m) {
                swap(nums1, i, nums2);
            }
            // copy from nums2
            if (i >= m) {
                nums1[i] = nums2[y++];
            }
        }
    }

    private void swap(int[] nums1, int id, int[] nums2) {
        if (nums1[id] > nums2[0]) {
            int temp = nums1[id];
            nums1[id] = nums2[0];
            nums2[0] = temp;

            // shift 1 element to make sure array is still sorted
            for (int i = 0; i < nums2.length - 1; i++) {
                if (nums2[i] > nums2[i + 1]) {
                    int temp2 = nums2[i];
                    nums2[i] = nums2[i + 1];
                    nums2[i + 1] = temp2;
                } else {
                    break;
                }
            }
        }
    }
}
