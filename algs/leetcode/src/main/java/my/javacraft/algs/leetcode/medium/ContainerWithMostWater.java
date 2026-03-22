package my.javacraft.algs.leetcode.medium;

/*
 * 11. Container With Most Water
 *
 * LeetCode: https://leetcode.com/problems/container-with-most-water/description/
 *
 * You are given an integer array height of length n.
 * There are n vertical lines drawn such that the two endpoints of the ith line are (i, 0) and (i, height[i]).
 *
 * Find two lines that together with the x-axis form a container, such that the container contains the most water.
 *
 * Return the maximum amount of water a container can store.
 *
 * Notice that you may not slant the container.
 *
 * For example
 *
 * Index:   0   1   2   3   4   5   6   7   8
 * Value:   1   8   6   2   5   4   8   3   7
 *
 * Bars:
 * 0: █
 * 1: ████████   (R)
 * 2: ██████
 * 3: ██
 * 4: █████
 * 5: ████
 * 6: ████████
 * 7: ███
 * 8: ███████   (R)
 *
 * Legend:
 *
 * █ = height
 * (R) = red bars (edges)
 * Blue area ≈ filled between height 7
 */
public class ContainerWithMostWater {

    public int maxArea(int[] heights) {
        int maxWaterVolume = 0;

        int i = 0;
        int j = heights.length - 1;
        while (i < j) {
            int leftHeight = heights[i];
            int rightHeight = heights[j];
            int height = Math.min(leftHeight, rightHeight);
            int waterVolume = height * (j - i);
            if (maxWaterVolume < waterVolume) {
                maxWaterVolume = waterVolume;
            }
            if (leftHeight <= rightHeight) {
                i++;
            } else {
                j--;
            }
        }
        return maxWaterVolume;
    }

}
