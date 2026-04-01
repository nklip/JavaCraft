package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 111. Minimum Depth of Binary Tree
 * <p>
 * Given a binary tree, find its minimum depth.
 * <p>
 * The minimum depth is the number of nodes along the shortest path from the root node down to the nearest leaf node.
 * <p>
 * Note: A leaf is a node with no children.
 */
public class MinimumDepthOfBinaryTree {

    public int minDepth(TreeNode root) {
        if (root == null) {
            return 0;
        }
        return minDepth(root, 0);
    }

    int minDepth(TreeNode root, int depth) {
        if (isLeaf(root)) {
            return depth + 1;
        }
        int leftDepth = -1;
        int rightDepth = -1;
        if (root.left != null) {
            leftDepth = minDepth(root.left, depth + 1);
        }
        if (root.right != null) {
            rightDepth = minDepth(root.right, depth + 1);
        }

        if (leftDepth == -1 || rightDepth == -1) {
            return Math.max(leftDepth, rightDepth);
        } else {
            return Math.min(leftDepth, rightDepth);
        }
    }

    boolean isLeaf(TreeNode root) {
        return root.left == null && root.right == null;
    }

}
