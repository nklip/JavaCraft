package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;

/*
 * 222. Count Complete Tree Nodes
 *
 * LeetCode: https://leetcode.com/problems/count-complete-tree-nodes/
 *
 * Given the root of a complete binary tree, return the number of the nodes in the tree.
 *
 * According to Wikipedia, every level, except possibly the last, is completely filled
 * in a complete binary tree, and all nodes in the last level are as far left as possible.
 * It can have between 1 and 2h nodes inclusive at the last level h.
 *
 * Design an algorithm that runs in less than O(n) time complexity.
 */
public class CountCompleteTreeNodes {

    /*
     * How it works.
     *
     * To design an algorithm that runs less than O(n) we should split tree in half every time.
     * So we should be able to split tree on subtrees (left and right)
     * If left subtree is full, then we check right subtree.
     * If left subtree is not full, we go into left subtree and check subtrees of left subtree.
     */
    public int countNodes(final TreeNode root) {
        if (root == null) {
            return 0;
        }

        int leftDepth = findDepth(root.left);
        int rightDepth = findDepth(root.right);

        if (leftDepth == rightDepth) {
            // return ((int)Math.pow(2, leftDepth)) + countNodes(root.right);
            // the below line is the same, but faster
            return (1 << leftDepth) + countNodes(root.right);
        } else {
            // return ((int)Math.pow(2, rightDepth)) + countNodes(root.left);
            // the below line is the same, but faster
            return (1 << rightDepth) + countNodes(root.left);
        }
    }

    private int findDepth(TreeNode root) {
        int depth = 0;
        while (root != null) {
            depth++;
            root = root.left;
        }
        return depth;
    }

}
