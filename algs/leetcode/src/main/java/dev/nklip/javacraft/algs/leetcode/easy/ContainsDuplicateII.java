package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * 219. Contains Duplicate II
 *
 * LeetCode: https://leetcode.com/problems/contains-duplicate-ii/
 *
 * Given an integer array nums and an integer k, return true if there are two distinct
 * indices i and j in the array such that nums[i] == nums[j] and abs(i - j) <= k.
 */
public class ContainsDuplicateII {

    // TODO: add sliding window solution

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
