package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BalancedBinaryTreeTest {

    /**
     *       3
     *      / \
     *     9  20
     *       /  \
     *      15   7
     */
    @Test
    void testCase1() {
        BalancedBinaryTree solution = new BalancedBinaryTree();

        TreeNode treeNode3 = new TreeNode(3);
        TreeNode treeNode9 = new TreeNode(9);
        TreeNode treeNode20 = new TreeNode(20);
        TreeNode treeNode15 = new TreeNode(15);
        TreeNode treeNode7 = new TreeNode(7);

        treeNode3.left = treeNode9;
        treeNode3.right = treeNode20;

        treeNode20.left = treeNode15;
        treeNode20.right = treeNode7;

        Assertions.assertTrue(solution.isBalanced(treeNode3));
    }

    /**
     *         1
     *        / \
     *       2   2
     *      / \
     *     3   3
     *    / \
     *   4   4
     */
    @Test
    void testCase2() {
        BalancedBinaryTree solution = new BalancedBinaryTree();

        TreeNode treeNode1 = new TreeNode(1);
        TreeNode treeNode12 = new TreeNode(2);
        TreeNode treeNode22 = new TreeNode(2);
        TreeNode treeNode13 = new TreeNode(3);
        TreeNode treeNode23 = new TreeNode(3);
        TreeNode treeNode14 = new TreeNode(4);
        TreeNode treeNode24 = new TreeNode(4);

        treeNode1.left = treeNode12;
        treeNode1.right = treeNode22;

        treeNode12.left = treeNode13;
        treeNode12.right = treeNode23;

        treeNode13.left = treeNode14;
        treeNode13.right = treeNode24;

        Assertions.assertFalse(solution.isBalanced(treeNode1));
    }

    @Test
    void testCase3() {
        BalancedBinaryTree solution = new BalancedBinaryTree();

        Assertions.assertTrue(solution.isBalanced(null));
    }
}
