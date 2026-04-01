package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.*;
import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/**
 * 94. Binary Tree Inorder Traversal
 * <p>
 * Given the root of a binary tree, return the inorder traversal of its nodes' values.
 * For example:
 * Tree:
 *       1
 *        \
 *         2
 *        /
 *       3
 * <p>
 * Step-by-step traversal:
 * <p>
 * 1) Start at node 1
 * 2) Go left → none
 * 3) Visit 1
 * 4) Go right → node 2
 * 5) Go left → 3
 * 6) Visit 3
 * 7) Visit 2
 **/
public class BinaryTreeInorderTraversal {

    //-------------------------
    // Iterative solution
    //-------------------------
    public List<Integer> inorderTraversal(TreeNode root) {
        List<Integer> returnList = new ArrayList<>();

        Stack<TreeNode> stack = new Stack<>();
        // how it works
        while (root != null || !stack.isEmpty()) {
            // go left as far as possible
            while (root != null) {
                // push all nodes (include the leaf) in stack along the way till we find the deepest left leaf
                stack.push(root);
                // when root.left is empty we leave the cycle
                root = root.left;
            }

            // we go back and start using the last node
            // for example, the deepest left leaf included
            root = stack.pop();
            returnList.add(root.val);

            // then we start checking the right path
            root = root.right;
        }
        return returnList;
    }

    //-------------------------
    // Recursive solution
    //-------------------------
    public List<Integer> inorderTraversalRecursive(TreeNode root) {
        List<Integer> returnList = new ArrayList<>();
        if (root != null) {
            inorderTraversalRecursive(root, returnList);
        }
        return returnList;
    }

    private void inorderTraversalRecursive(TreeNode root, List<Integer> list) {
        if (root.left != null) {
            inorderTraversalRecursive(root.left, list);
        }
        list.add(root.val);
        if (root.right != null) {
            inorderTraversalRecursive(root.right, list);
        }
    }

}
