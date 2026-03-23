package my.javacraft.algs.leetcode.medium;

import java.util.*;

/*
 * 15. 3Sum
 *
 * LeetCode: https://leetcode.com/problems/3sum/
 *
 * Given an integer array nums, return all the triplets [nums[i], nums[j], nums[k]]
 * such that i != j, i != k, and j != k, and nums[i] + nums[j] + nums[k] == 0.
 *
 * Notice that the solution set must not contain duplicate triplets.
 */
@SuppressWarnings("Duplicates")
public class ThreeSum {

    public List<List<Integer>> threeSum(int[] nums) {
        Arrays.sort(nums);

        List<List<Integer>> result = new ArrayList<>();

        for (int i = 0; i < nums.length - 2; i++) {
            int j = i + 1;
            int k = nums.length - 1;

            // skip duplicates
            if (i > 0 && nums[i] == nums[i-1]) {
                continue;
            }
            while (j < k) {
                int sum = nums[i] + nums[j] + nums[k];
                if (sum == 0) {
                    result.add(Arrays.asList(nums[i], nums[j], nums[k]));
                    // we should increase left pointer and decrease right pointer
                    j++;
                    k--;
                } else if (sum < 0) {
                    // we should increase left pointer
                    j++;
                } else {
                    // sum > 0 -> we should decrease right pointer
                    k--;
                }
            }

        }
        return result;
    }


    // n^2
    public List<List<Integer>> threeSum2(int[] nums) {
        // n
        Map<Integer, List<Integer>> temp = new HashMap<>();
        for (int i = 0; i < nums.length; i++) {
            int value = nums[i];

            List<Integer> list = new ArrayList<>();
            if (temp.containsKey(value)) {
                list = temp.get(value);
            }
            list.add(i);
            temp.put(value, list);
        }

        // n^2
        Set<List<Integer>> preResult = new HashSet<>();
        for (int i = 0; i < nums.length; i++) {
            int value1 = nums[i];

            for (int j = i + 1; j < nums.length; j++) {
                int value2 = nums[j];

                int delta = value1 + value2;
                int seekValue = -delta;

                if (temp.containsKey(seekValue)) {
                    List<Integer> indexes = temp.get(seekValue);
                    int k = indexes.getFirst();

                    if (i != j && j != k && i != k) {
                        List<Integer> list = new ArrayList<>();
                        list.add(nums[i]);
                        list.add(nums[j]);
                        list.add(nums[k]);
                        Collections.sort(list);
                        preResult.add(list);
                    }
                }
            }
        }

        return new ArrayList<>(preResult);
    }
}
