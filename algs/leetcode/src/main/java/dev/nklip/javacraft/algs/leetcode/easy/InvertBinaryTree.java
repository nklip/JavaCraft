package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import java.util.Stack;

public class InvertBinaryTree {

    public TreeNode invertTree(TreeNode root) {
        if (root == null) {
            return null;
        }
        TreeNode targetRoot = new TreeNode(root.val);
        TreeNode returnNode = targetRoot;
        Stack<TreeNode> sourceStack = new Stack<>();
        Stack<TreeNode> targetStack = new Stack<>();

        while (root != null || !sourceStack.isEmpty()) {
            while (root != null) {
                if (root.left != null) {
                    targetRoot.right = new TreeNode(root.left.val);
                }
                sourceStack.push(root);
                targetStack.push(targetRoot);

                root = root.left;
                targetRoot = targetRoot.right;
            }

            root = sourceStack.pop();
            targetRoot = targetStack.pop();

            if (root.right != null) {
                targetRoot.left = new TreeNode(root.right.val);
            }
            root = root.right;
            targetRoot = targetRoot.left;
        }
        return returnNode;
    }

    public TreeNode invertTreeRecursive(TreeNode root) {
        if (root == null) {
            return null;
        }

        TreeNode targetRoot = new TreeNode(root.val);
        invertTree(root, targetRoot);
        return targetRoot;
    }

    public void invertTree(TreeNode sourceNode, TreeNode targetNode) {
        if (sourceNode == null) {
            return;
        }

        if (sourceNode.left != null) {
            targetNode.right = new TreeNode(sourceNode.left.val);
            invertTree(sourceNode.left, targetNode.right);
        }
        if (sourceNode.right != null) {
            targetNode.left = new TreeNode(sourceNode.right.val);
            invertTree(sourceNode.right, targetNode.left);
        }
    }

}
