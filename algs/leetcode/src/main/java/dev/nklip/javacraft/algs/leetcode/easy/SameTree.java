package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 100. Same Tree
 * <p>
 * Given the roots of two binary trees p and q, write a function to check if they are the same or not.
 * <p>
 * Two binary trees are considered the same if they are structurally identical, and the nodes have the same value.
 */
public class SameTree {

    public boolean isSameTree(TreeNode p, TreeNode q) {
        if (p == null && q == null) {
            return true;
        } else if (p != null && q == null) {
            return false;
        } else if (p == null) { // is' already always: q != null
            return false;
        } else {
            if (p.val != q.val) {
                return false;
            } else {
                boolean leftResult = isSameTree(p.left, q.left);
                boolean rightResult = isSameTree(p.right, q.right);

                return p.val == q.val && leftResult && rightResult;
            }
        }
    }

}
