package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 35. Search Insert Position
 * <p>
 * Given a sorted array of distinct integers and a target value, return the index if the target is found.
 * If not, return the index where it would be if it were inserted in order.
 * <p>
 * You must write an algorithm with O(log n) runtime complexity.
 */
public class SearchInsertPosition {

    public int searchInsert(int[] nums, int target) {
        return binarySearch(nums, target, 0, nums.length - 1);
    }

    int binarySearch(int[] nums, int target, int lp, int rp) {
        int medId = ((rp - lp) / 2) + lp;
        int medValue = nums[medId];

        if (medValue == target) {
            return medId;
        } else if (rp - lp <= 2) {
            if (nums[lp] >= target) {
                return lp;
            } else if (nums[rp] == target) {
                return rp;
            } else if (nums[rp] < target){
                return rp + 1;
            }
        }

        if (medValue < target) { // right side
            return binarySearch(nums, target, medId + 1 , rp);
        } else { // left side
            return binarySearch(nums, target, lp, medId);
        }
    }

}
