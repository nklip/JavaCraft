package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.List;

/*
 * 228. Summary Ranges
 *
 * LeetCode: https://leetcode.com/problems/summary-ranges/
 *
 * You are given a sorted unique integer array nums.
 *
 * A range [a,b] is the set of all integers from a to b (inclusive).
 *
 * Return the smallest sorted list of ranges that cover all the numbers in the array exactly.
 * That is, each element of nums is covered by exactly one of the ranges,
 * and there is no integer x such that x is in one of the ranges but not in nums.
 *
 * Each range [a,b] in the list should be output as:
 *
 * "a->b" if a != b
 * "a" if a == b
 */
public class SummaryRanges {

    public List<String> summaryRanges(int[] nums) {
        if (nums == null || nums.length == 0) {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>();

        boolean singleDigit = true;
        int start = nums[0];
        int checkValue = start;
        for (int i = 1; i < nums.length; i++) {
            int end = nums[i];
            if (end == checkValue + 1) {
                singleDigit = false;
                checkValue = end;
            } else {
                addRange(list, singleDigit, start, nums[i - 1]);

                singleDigit = true;
                if (i <= nums.length - 1) {
                    start = nums[i];
                    checkValue = start;
                }
            }

            // last action in cycle
            if (i == nums.length - 1) {
                addRange(list, singleDigit, start, nums[i]);
            }
        }
        if (list.isEmpty()) {
            list.add(Integer.toString(start));
        }

        return list;
    }

    private void addRange(List<String> list, boolean singleDigit, int start, int end) {
        if (singleDigit) {
            list.add(Integer.toString(start));
        } else {
            list.add("%s->%s".formatted(start, end));
        }
    }

}
