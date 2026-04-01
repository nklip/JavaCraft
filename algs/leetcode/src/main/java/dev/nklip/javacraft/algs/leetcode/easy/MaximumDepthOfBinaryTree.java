package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 104. Maximum Depth of Binary Tree
 * <p>
 * Given the root of a binary tree, return its maximum depth.
 * <p>
 * A binary tree's maximum depth is the number of nodes along the longest path from the root node down to the farthest leaf node.
 */
public class MaximumDepthOfBinaryTree {

    public int maxDepth(TreeNode root) {
        if (root == null) {
            return 0;
        }
        return findMaxDepth(root, 1);
    }

    private int findMaxDepth(TreeNode root, final int maxDepth) {
        int depth = maxDepth;
        if (root.left != null) {
            int temp = findMaxDepth(root.left, maxDepth + 1);
            if (temp > depth) {
                depth = temp;
            }
        }
        if (root.right != null) {
            int temp = findMaxDepth(root.right, maxDepth + 1);
            if (temp > depth) {
                depth = temp;
            }
        }
        return depth;
    }

}
