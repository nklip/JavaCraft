package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 108. Convert Sorted Array to Binary Search Tree
 * <p>
 * Given an integer array nums where the elements are sorted in ascending order,
 * convert it to a height-balanced binary search tree.
 * <p>
 * Height-Balanced - a height-balanced binary tree is a binary tree in which
 * the depth of the two subtrees of every node never differs by more than one.
 */
public class ConvertSortedArrayToBinarySearchTree {

    public TreeNode sortedArrayToBST(int[] nums) {
        int rootId = nums.length / 2;
        TreeNode rootNode = new TreeNode(nums[rootId]);

        arrayToBST(nums, rootNode);

        return rootNode;
    }

    private void leftArrayToBST(int[] nums, TreeNode root) {
        int rootId = nums.length / 2;

        root.left = new TreeNode(nums[rootId]);

        arrayToBST(nums, root.left);
    }

    private void rightArrayToBST(int[] nums, TreeNode root) {
        int rootId = nums.length / 2;

        root.right = new TreeNode(nums[rootId]);

        arrayToBST(nums, root.right);
    }

    private void arrayToBST(int []nums, TreeNode root) {
        // prepare left array
        int []leftArray = new int[nums.length / 2];
        System.arraycopy(nums, 0, leftArray, 0, leftArray.length);

        // prepare right array
        int rightArrayStart = (nums.length / 2) + 1;
        int []rightArray = new int[nums.length - rightArrayStart];
        System.arraycopy(nums, rightArrayStart, rightArray, 0, rightArray.length);

        if (leftArray.length > 0) {
            leftArrayToBST(leftArray, root);
        }
        if (rightArray.length > 0) {
            rightArrayToBST(rightArray, root);
        }
    }

}
