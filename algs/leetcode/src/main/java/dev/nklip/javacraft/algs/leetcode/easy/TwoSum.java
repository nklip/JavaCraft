package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/*
 * 1. Two Sum
 *
 * LeetCode: https://leetcode.com/problems/two-sum
 *
 * Given an array of integers nums and an integer target,
 * return indices of the two numbers such that they add up to target.
 *
 * You may assume that each input would have exactly one solution, and you may not use the same element twice.
 *
 * You can return the answer in any order.
 */
@SuppressWarnings("Duplicates")
public class TwoSum {

    public int[] twoSum(int[] nums, int target) {
        int []output = new int[2];

        HashMap<Integer, List<Integer>> temp = new LinkedHashMap<>();
        for (int i = 0; i < nums.length; i++) {
            int currNum = nums[i];

            List<Integer> list = new ArrayList<>();
            if (temp.containsKey(currNum)) {
                list = temp.get(currNum);
            }
            list.add(i);
            temp.put(currNum, list);
        }

        for (int firstValue : temp.keySet()) {
            List<Integer> firstPos = temp.get(firstValue);

            int lookingFor = target - firstValue;

            if (temp.containsKey(lookingFor)) {

                List<Integer> secondPos = temp.get(lookingFor);

                if (firstValue != lookingFor) {
                    output[0] = firstPos.getFirst();
                    output[1] = secondPos.getFirst();
                    break;
                } else {
                    if (secondPos.size() > 1) {
                        output[0] = firstPos.getFirst();
                        output[1] = secondPos.getLast();
                        break;
                    }
                }
            }

        }

        return output;
    }

    // simple - O(n2) time complexity
    public int[] twoSum2(int[] nums, int target) {
        int []output = new int[2];

        for (int i = 0; i < nums.length; i++) {
            int first = nums[i];
            for (int y = i + 1; y < nums.length; y++) {
                int second = nums[y];

                int sum = first + second;
                if (sum == target) {
                    output[0] = i;
                    output[1] = y;
                    break;
                }
            }

        }

        return output;
    }

}
