package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/*
 * 257. Binary Tree Paths
 *
 * LeetCode: https://leetcode.com/problems/binary-tree-paths/
 *
 * Given the root of a binary tree, return all root-to-leaf paths in any order.
 *
 * A leaf is a node with no children.
 */
public class BinaryTreePaths {

    // DFS
    public List<String> binaryTreePaths(TreeNode root) {
        List<String> result = new ArrayList<>();

        Stack<TreeNode> stack = new Stack<>();
        Stack<String> paths = new Stack<>();

        stack.push(root);
        paths.push("");

        while (!stack.isEmpty()) {
            TreeNode node = stack.pop();
            String path = paths.pop();

            if (isLeaf(node)) {
                result.add(path + node.val);
            }
            if (node.left != null) {
                stack.push(node.left);
                paths.push(path + node.val + "->");
            }
            if (node.right != null) {
                stack.push(node.right);
                paths.push(path + node.val + "->");
            }
        }

        return result;
    }

    public List<String> binaryTreePathsRecursive(TreeNode root) {
        List<String> result = new ArrayList<>();

        if (isLeaf(root)) {
            result.add(String.valueOf(root.val));
        } else {
            binaryTreePaths(result, Integer.toString(root.val), root.left);
            binaryTreePaths(result, Integer.toString(root.val), root.right);
        }

        return result;
    }

    public void binaryTreePaths(List<String> list, String pathSoFar, TreeNode root) {
        if (root == null) {
            return;
        }
        pathSoFar += "->" + root.val;
        if (isLeaf(root)) {
            list.add(pathSoFar);
            return;
        }

        if (root.left != null) {
            binaryTreePaths(list, pathSoFar, root.left);
        }
        if (root.right != null) {
            binaryTreePaths(list, pathSoFar, root.right);
        }
    }

    private boolean isLeaf(TreeNode root) {
        return root.left == null && root.right == null;
    }
}
