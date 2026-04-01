package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 112. Path Sum
 * <p>
 * Given the root of a binary tree and an integer targetSum,
 * return true if the tree has a root-to-leaf path such that adding up all the values along the path equals targetSum.
 * <p>
 * A leaf is a node with no children.
 */
public class PathSum {

    public boolean hasPathSum(TreeNode root, final int targetSum) {
        if (root == null) {
            return false;
        }
        return hasPathSum(root, targetSum, root.val);
    }

    boolean hasPathSum(TreeNode root, final int targetSum, final int currSum) {
        if (isLeaf(root)) {
            if (targetSum == currSum) {
                return true;
            }
        }
        boolean isLeftPath = false;
        boolean isRightPath = false;
        if (root.left != null) {
            int thisSum = currSum + root.left.val;
            isLeftPath = hasPathSum(root.left, targetSum, thisSum);
        }
        if (root.right != null) {
            int thisSum = currSum + root.right.val;
            isRightPath = hasPathSum(root.right, targetSum, thisSum);
        }
        return isLeftPath || isRightPath;
    }

    boolean isLeaf(TreeNode node) {
        return node.left == null && node.right == null;
    }

}
