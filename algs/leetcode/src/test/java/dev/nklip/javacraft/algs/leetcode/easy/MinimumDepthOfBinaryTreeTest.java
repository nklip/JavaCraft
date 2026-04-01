package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MinimumDepthOfBinaryTreeTest {
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

        MinimumDepthOfBinaryTree solution = new MinimumDepthOfBinaryTree();

        Assertions.assertEquals(2, solution.minDepth(treeNode3));
    }

    /**
     * 2
     *  \
     *   3
     *    \
     *     4
     *      \
     *       5
     *        \
     *         6
     */
    @Test
    void testCase2() {
        TreeNode treeNode2 = new TreeNode(2);
        TreeNode treeNode3 = new TreeNode(3);
        TreeNode treeNode4 = new TreeNode(4);
        TreeNode treeNode5 = new TreeNode(5);
        TreeNode treeNode6 = new TreeNode(6);

        treeNode2.right = treeNode3;
        treeNode3.right = treeNode4;
        treeNode4.right = treeNode5;
        treeNode5.right = treeNode6;

        MinimumDepthOfBinaryTree solution = new MinimumDepthOfBinaryTree();

        Assertions.assertEquals(5, solution.minDepth(treeNode2));
    }


}
