package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.Arrays;

/**
 * 169. Majority Element
 * <p>
 * Given an array nums of size n, return the majority element.
 * <p>
 * The majority element is the element that appears more than ⌊n / 2⌋ times.
 * You may assume that the majority element always exists in the array.
 */
public class MajorityElement {

    public int majorityElement(int[] nums) {
        // O(n log(n)) - uses QuickSort
        Arrays.sort(nums);

        int majority = -1;
        int length = nums.length / 2;
        for (int i = 0; i <= length; i++) {
            int temp = nums[i];
            int lastId = nums[i + length];
            if (temp == lastId) {
                majority = temp;
                break;
            }
        }
        return majority;
    }

    /**
     * Moore’s Voting Algorithm (often called Boyer–Moore Voting Algorithm) is an algorithm
     * used to find the majority element in an array.
     * <p>
     * Time complexity - O(n)
     * <p>
     * If you pair up different elements, the majority element will still remain because it appears more than half of the time.
     * <p>
     * The algorithm keeps:
     * <p>
     * 1) a candidate
     * 2) a count
     * <p>
     * When count reaches 0, we pick a new candidate.
     */
    public int majorityElementMoore(int[] nums) {
        int candidate = 0;
        int count = 0;

        for (int num : nums) {
            // 0 means that a candidate didn't work out
            // it also means that the amount of different numbers
            // in the previous part of the array is more than half
            // which means we can discard that part of an array
            if (count == 0) {
                candidate = num;
            }
            if (num == candidate) {
                count++;
            } else {
                count--;
            }
        }
        return candidate;
    }

}
