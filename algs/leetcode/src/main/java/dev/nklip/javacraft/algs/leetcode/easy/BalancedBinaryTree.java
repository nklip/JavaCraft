package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 110. Balanced Binary Tree
 * <p>
 * Given a binary tree, determine if it is height-balanced.
 * <p>
 * Height-balanced - a height-balanced binary tree is a binary tree in which the depth of the two subtrees of every
 * node never differs by more than one.
 */
public class BalancedBinaryTree {

    public boolean isBalanced(TreeNode root) {
        if (root == null) {
            return true;
        }
        int leftDepth = maxSubtreeDepth(root.left, 1);
        int rightDepth = maxSubtreeDepth(root.right, 1);

        if (leftDepth == -1 || rightDepth == -1) {
            return false;
        }

        int diff = Math.abs(leftDepth - rightDepth);
        return diff < 2;
    }

    int maxSubtreeDepth(TreeNode root, int depth) {
        int leftDepth = depth;
        int rightDepth = depth;
        if (root != null) {
            leftDepth = maxSubtreeDepth(root.left, depth + 1);
            rightDepth = maxSubtreeDepth(root.right, depth + 1);
            int diff = Math.abs(leftDepth - rightDepth);
            if (diff > 1) {
                return -1; // not balanced
            }
        }
        return Math.max(leftDepth, rightDepth);
    }
}
