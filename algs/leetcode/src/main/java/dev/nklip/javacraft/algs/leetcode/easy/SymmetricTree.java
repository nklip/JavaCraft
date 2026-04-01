package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 101. Symmetric Tree
 * <p>
 * Given the root of a binary tree, check whether it is a mirror of itself (i.e., symmetric around its center).
 */
public class SymmetricTree {

    public boolean isSymmetric(TreeNode root) {
        return !notSymmetric(root.left, root.right);
    }

    private boolean notSymmetric(TreeNode left, TreeNode right) {
        if (left == null && right == null) {
            return false;
        } else if (left == null) {
            return true;
        } else if (right == null) {
            return true;
        } else if (left.val != right.val) {
            return true;
        }
        boolean notSymmetric = false;
        if (left.left != null && right.right != null) {
            notSymmetric = notSymmetric(left.left, right.right);
        } else if (left.left == null && right.right != null) {
            notSymmetric = true;
        } else if (left.left != null) {
            notSymmetric = true;
        }

        if (notSymmetric) {
            return notSymmetric;
        }

        if (left.right != null && right.left != null) {
            notSymmetric = notSymmetric(left.right, right.left);
        } else if (left.right == null && right.left != null) {
            notSymmetric = true;
        } else if (left.right != null) {
            notSymmetric = true;
        }

        return notSymmetric;
    }

    //-------------------------
    // Iterative solution
    //-------------------------
    public boolean isSymmetricIterative(TreeNode root) {
        List<Integer> leftOrder = new ArrayList<>();
        List<Integer> rightOrder = new ArrayList<>();

        Stack<TreeNode> leftStack = new Stack<>();
        Stack<TreeNode> rightStack = new Stack<>();

        TreeNode leftRoot = root.left;
        while (leftRoot != null || !leftStack.isEmpty()) {
            while (leftRoot != null) {
                leftStack.push(leftRoot);
                leftRoot = leftRoot.left;
            }

            leftRoot = leftStack.pop();
            leftOrder.add(leftRoot.val);

            leftRoot = leftRoot.right;
            leftOrder.add(leftRoot == null ? null : leftRoot.val);
        }

        TreeNode rightRoot = root.right;
        while (rightRoot != null || !rightStack.isEmpty()) {
            while (rightRoot != null) {
                rightStack.push(rightRoot);
                rightRoot = rightRoot.right;
            }

            rightRoot = rightStack.pop();
            rightOrder.add(rightRoot.val);

            rightRoot = rightRoot.left;
            rightOrder.add(rightRoot == null ? null : rightRoot.val);
        }

        return leftOrder.toString().equals(rightOrder.toString());
    }

}
