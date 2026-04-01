package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.HashSet;
import java.util.Set;

/**
 * 136. Single Number
 * <p>
 * Given a non-empty array of integers nums, every element appears twice except for one.
 * Find that single one.
 * <p>
 * You must implement a solution with a linear runtime complexity and use only constant extra space.
 */
public class SingleNumber {

    /**
     * XOR all elements in the array.
     * Duplicate numbers cancel out.
     * The remaining value is the single number.
     * <p>
     * a ^ a = 0
     * a ^ 0 = a
     * <p>
     * time complexity - O (n)
     */
    public int singleNumber(int[] nums) {
        int result = 0;

        for (int num : nums) {
            result ^= num;
        }

        return result;
    }

    // use set time complexity - O(n log(n))
    public int singleNumber2(int[] nums) {
        Set<Integer> singleSet = new HashSet<>();

        for (int num : nums) {
            if (!singleSet.contains(num)) {
                singleSet.add(num);
            } else {
                singleSet.remove(num);
            }
        }

        return singleSet.iterator().next();
    }

}
