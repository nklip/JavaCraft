package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MaximumDepthOfBinaryTreeTest {


    /**
     *         3
     *        / \
     *       9   20
     *          /  \
     *         15   7
     */
    @Test
    void testCase1() {
        TreeNode treeNode3 = new TreeNode(3);
        TreeNode treeNode9 = new TreeNode(9);
        TreeNode treeNode20 = new TreeNode(20);
        TreeNode treeNode15 = new TreeNode(15);
        TreeNode treeNode7 = new TreeNode(7);

        treeNode3.left = treeNode9;
        treeNode3.right = treeNode20;

        treeNode20.left = treeNode15;
        treeNode20.right = treeNode7;

        MaximumDepthOfBinaryTree solution = new MaximumDepthOfBinaryTree();

        Assertions.assertEquals(3, solution.maxDepth(treeNode3));
    }

    /**
     *     1
     *      \
     *       2
     */
    @Test
    void testCase2() {
        TreeNode treeNode1 = new TreeNode(1);

        treeNode1.right = new TreeNode(2);

        MaximumDepthOfBinaryTree solution = new MaximumDepthOfBinaryTree();

        Assertions.assertEquals(2, solution.maxDepth(treeNode1));
    }

    /**
     *         1
     *       /   \
     *      2     3
     *     / \
     *    4   5
     */
    @Test
    void testCase3() {
        TreeNode treeNode1 = new TreeNode(1);
        TreeNode treeNode2 = new TreeNode(2);
        TreeNode treeNode3 = new TreeNode(3);
        TreeNode treeNode4 = new TreeNode(4);
        TreeNode treeNode5 = new TreeNode(5);

        treeNode1.left = treeNode2;
        treeNode1.right = treeNode3;

        treeNode2.left = treeNode4;
        treeNode2.right = treeNode5;

        MaximumDepthOfBinaryTree solution = new MaximumDepthOfBinaryTree();

        // [1,2,3,4,5]
        Assertions.assertEquals(3, solution.maxDepth(treeNode1));
    }
}
