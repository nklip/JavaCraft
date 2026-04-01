package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 145. Binary Tree Postorder Traversal
 * <p>
 * Given the root of a binary tree, return the postorder traversal of its nodes' values.
 */
public class BinaryTreePostorderTraversal {

    public List<Integer> postorderTraversal(TreeNode root) {
        List<Integer> list = new ArrayList<>();
        if (root == null) {
            return list;
        }

        Stack<TreeNode> stack = new Stack<>();
        TreeNode lastVisited = null;

        // left -> right -> root
        while (root != null || !stack.isEmpty()) {
            if (root != null) {
                stack.push(root);
                root = root.left;
            } else {
                TreeNode peekNode = stack.getLast();
                if (peekNode.right != null && lastVisited != peekNode.right) {
                    root = peekNode.right;
                } else {
                    // add value if it's a leaf or all leafs already added
                    list.add(peekNode.val);
                    lastVisited = peekNode;
                    stack.pop();
                }
            }
        }
        return list;
    }

    public List<Integer> postorderTraversalRecursive(TreeNode root) {
        List<Integer> list = new ArrayList<>();
        if (root == null) {
            return list;
        }
        postorderTraversalRecursive(root, list);
        return list;
    }

    private void postorderTraversalRecursive(TreeNode root, List<Integer> list) {
        if (root == null) {
            return;
        }
        if (isLeaf(root)) {
            list.add(root.val);
            return;
        }
        if (root.left != null) {
            postorderTraversalRecursive(root.left, list);
        }
        if (root.right != null) {
            postorderTraversalRecursive(root.right, list);
        }
        list.add(root.val);
    }

    private boolean isLeaf(TreeNode node) {
        return node != null && (node.left == null && node.right == null);
    }



}
