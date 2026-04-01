package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MergeSortedArrayTest {

    @Test
    void testCase1() {
        MergeSortedArray solution = new MergeSortedArray();
        int[] nums1 = {1,2,3,0,0,0};
        int[] nums2 = {2,5,6};
        int[] expectedArray = {1,2,2,3,5,6};
        // execution
        solution.merge(nums1, 3, nums2, 3);
        // verification
        Assertions.assertArrayEquals(expectedArray, nums1);
    }

    @Test
    void testCase2() {
        MergeSortedArray solution = new MergeSortedArray();
        int[] nums1 = {1};
        int[] nums2 = {};
        int[] expectedArray = {1};
        // execution
        solution.merge(nums1, 1, nums2, 0);
        // verification
        Assertions.assertArrayEquals(expectedArray, nums1);
    }

    @Test
    void testCase3() {
        MergeSortedArray solution = new MergeSortedArray();
        int[] nums1 = {0};
        int[] nums2 = {1};
        int[] expectedArray = {1};
        // execution
        solution.merge(nums1, 0, nums2, 1);
        // verification
        Assertions.assertArrayEquals(expectedArray, nums1);
    }

    @Test
    void testCase4() {
        MergeSortedArray solution = new MergeSortedArray();
        int[] nums1 = {4,5,6,0,0,0};
        int[] nums2 = {1,2,3};
        int[] expectedArray = {1,2,3,4,5,6};
        // execution
        solution.merge(nums1, 3, nums2, 3);
        // verification
        Assertions.assertArrayEquals(expectedArray, nums1);
    }

    @Test
    void testCase5() {
        MergeSortedArray solution = new MergeSortedArray();
        int[] nums1 = {1,5,6,0,0,0};
        int[] nums2 = {1,4,7};
        int[] expectedArray = {1,1,4,5,6,7};
        // execution
        solution.merge(nums1, 3, nums2, 3);
        // verification
        Assertions.assertArrayEquals(expectedArray, nums1);
    }

    @Test
    void testCase6() {
        MergeSortedArray solution = new MergeSortedArray();
        int[] nums1 = {4,0,0,0,0,0};
        int[] nums2 = {1,2,3,5,6};
        int[] expectedArray = {1,2,3,4,5,6};
        // execution
        solution.merge(nums1, 1, nums2, 5);
        // verification
        Assertions.assertArrayEquals(expectedArray, nums1);
    }

    @Test
    void testCase7() {
        MergeSortedArray solution = new MergeSortedArray();
        int[] nums1 = {-10,-10,-9,-9,-9,-8,-8,-7,-7,-7,-6,-6,-6,-6,-6,-6,-6,-5,-5,-5,-4,-4,-4,-3,-3,-2,-2,-1,-1,0,1,1,1,2,2,2,3,3,3,4,5,5,6,6,6,6,7,7,7,7,8,9,9,9,9,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
        int[] nums2 = {-10,-10,-9,-9,-9,-9,-8,-8,-8,-8,-8,-7,-7,-7,-7,-7,-7,-7,-7,-6,-6,-6,-6,-5,-5,-5,-5,-5,-4,-4,-4,-4,-4,-3,-3,-3,-2,-2,-2,-2,-2,-2,-2,-1,-1,-1,0,0,0,0,0,1,1,1,2,2,2,2,2,2,2,2,3,3,3,3,4,4,4,4,4,4,4,5,5,5,5,5,5,6,6,6,6,6,7,7,7,7,7,7,7,8,8,8,8,9,9,9,9};
        int[] expectedArray = {-10,-10,-10,-10,-9,-9,-9,-9,-9,-9,-9,-8,-8,-8,-8,-8,-8,-8,-7,-7,-7,-7,-7,-7,-7,-7,-7,-7,-7,-6,-6,-6,-6,-6,-6,-6,-6,-6,-6,-6,-5,-5,-5,-5,-5,-5,-5,-5,-4,-4,-4,-4,-4,-4,-4,-4,-3,-3,-3,-3,-3,-2,-2,-2,-2,-2,-2,-2,-2,-2,-1,-1,-1,-1,-1,0,0,0,0,0,0,1,1,1,1,1,1,2,2,2,2,2,2,2,2,2,2,2,3,3,3,3,3,3,3,4,4,4,4,4,4,4,4,5,5,5,5,5,5,5,5,6,6,6,6,6,6,6,6,6,7,7,7,7,7,7,7,7,7,7,7,8,8,8,8,8,9,9,9,9,9,9,9,9};
        Assertions.assertEquals(154, nums1.length);
        Assertions.assertEquals(99, nums2.length);
        // execution
        solution.merge(nums1, 55, nums2, 99);
        // verification
        Assertions.assertArrayEquals(expectedArray, nums1);
    }
}
