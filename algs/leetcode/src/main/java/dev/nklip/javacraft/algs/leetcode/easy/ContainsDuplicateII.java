package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.*;

/*
 * 219. Contains Duplicate II
 *
 * LeetCode: https://leetcode.com/problems/contains-duplicate-ii/
 *
 * Given an integer array nums and an integer k, return true if there are two distinct
 * indices i and j in the array such that nums[i] == nums[j] and abs(i - j) <= k.
 */
public class ContainsDuplicateII {

    // sliding windows
    public boolean containsNearbyDuplicate(int[] nums, int k) {
        Set<Integer> slidingWindow = new HashSet<>();
        for (int i = 0; i < nums.length; i++) {
            // remove the first element when it leaves the sliding window
            if (i > k) {
                int temp = nums[i - k - 1];
                slidingWindow.remove(temp);
            }

            int value = nums[i];
            // duplicate value inside the sliding window
            if (!slidingWindow.add(value)) {
                return true;
            }

        }
        return false;
    }

    public boolean containsNearbyDuplicateUseHashMap(int[] nums, int k) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        // find all duplicates and their indexes
        for (int i = 0; i < nums.length; i++) {
            int num = nums[i];

            List<Integer> list;
            if (map.containsKey(num)) {
                list = map.get(num);
            } else {
                list = new ArrayList<>();
            }
            list.add(i);
            map.put(num, list);
        }

        for (Map.Entry<Integer, List<Integer>> entry : map.entrySet()) {
            if (entry.getValue().size() > 1) {
                // check duplicates abs
                List<Integer> values = entry.getValue();
                for (int i = 1; i < values.size(); i++) {
                    int prev = values.get(i - 1);
                    int curr = values.get(i);

                    if (Math.abs(prev - curr) <= k) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

}
