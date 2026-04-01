package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 144. Binary Tree Preorder Traversal
 * <p>
 * Given the root of a binary tree, return the preorder traversal of its nodes' values.
 * <p>
 * Preorder traversal is a depth-first tree traversal where you visit nodes in this order:
 * <p>
 * 1) Root
 * 2) Left subtree
 * 3) Right subtree
 * So the rule is: Root -> Left -> Right.
 */
public class BinaryTreePreorderTraversal {
    public List<Integer> preorderTraversal(TreeNode root) {
        List<Integer> list = new ArrayList<>();
        if (root == null) {
            return list;
        }

        Stack<TreeNode> stack = new Stack<>();
        while (root != null || !stack.isEmpty()) {
            // go left as far as possible
            while (root != null) {
                list.add(root.val);
                // push all nodes (include the leaf) in stack along the way till we find the deepest left leaf
                stack.push(root);
                // when root.left is empty we leave the cycle
                root = root.left;
            }

            // we go back and start using the last node
            // for example, the deepest left leaf included
            root = stack.pop();

            // then we start checking the right path
            root = root.right;
        }
        return list;
    }

    public List<Integer> preorderTraversalRecursive(TreeNode root) {
        List<Integer> list = new ArrayList<>();
        if (root == null) {
            return list;
        } else {
            list.add(root.val);
        }
        if (root.left != null) {
            preorderTraversal(root.left, list);
        }
        if (root.right != null) {
            preorderTraversal(root.right, list);
        }
        return list;
    }

    private void preorderTraversal(TreeNode root, List<Integer> list) {
        if (root == null) {
            return;
        } else {
            list.add(root.val);
        }
        if (root.left != null) {
            preorderTraversal(root.left, list);
        }
        if (root.right != null) {
            preorderTraversal(root.right, list);
        }
    }
}
