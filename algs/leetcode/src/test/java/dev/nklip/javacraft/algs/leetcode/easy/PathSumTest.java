package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PathSumTest {

    /**
     *             5
     *           /   \
     *          4     8
     *         /     / \
     *       11     13  4
     *      /  \         \
     *     7    2         1
     */
    @Test
    void testCase1() {
        TreeNode treeNode5 = new TreeNode(5);
        TreeNode treeNode41 = new TreeNode(4);
        TreeNode treeNode8 = new TreeNode(8);
        TreeNode treeNode11 = new TreeNode(11);
        TreeNode treeNode13 = new TreeNode(13);
        TreeNode treeNode42 = new TreeNode(4);
        TreeNode treeNode7 = new TreeNode(7);
        TreeNode treeNode2 = new TreeNode(2);
        TreeNode treeNode1 = new TreeNode(1);

        treeNode5.left = treeNode41;
        treeNode5.right = treeNode8;

        treeNode41.left = treeNode11;

        treeNode11.left = treeNode7;
        treeNode11.right = treeNode2;

        treeNode8.left = treeNode13;
        treeNode8.right = treeNode42;

        treeNode42.right = treeNode1;

        PathSum solution = new PathSum();
        // 5 -> 4 -> 11 -> 2 = 22
        Assertions.assertTrue(solution.hasPathSum(treeNode5, 22));
    }

    @Test
    void testCase2() {
        TreeNode treeNode1 = new TreeNode(1);
        TreeNode treeNode2 = new TreeNode(2);
        TreeNode treeNode3 = new TreeNode(3);

        treeNode1.left = treeNode2;
        treeNode2.right = treeNode3;

        PathSum pathSum = new PathSum();
        // There are two root-to-leaf paths in the tree:
        // (1 --> 2): The sum is 3.
        // (1 --> 3): The sum is 4.
        // There is no root-to-leaf path with sum = 5.
        Assertions.assertFalse(pathSum.hasPathSum(treeNode1, 5));
    }

    @Test
    void testCase3() {
        PathSum pathSum = new PathSum();

        // since the tree is empty, there are no root-to-leaf paths.
        Assertions.assertFalse(pathSum.hasPathSum(null, 0));
    }

    /**
     *           1
     *          / \
     *        /    \
     *      -2     -3
     *      / \    /
     *     1   3 -2
     *    /
     *  -1
     */
    @Test
    void testCase4() {
        TreeNode treeNode1 = new TreeNode(1);
        TreeNode treeNode21 = new TreeNode(-2);
        TreeNode treeNode22 = new TreeNode(-3);
        TreeNode treeNode31 = new TreeNode(1);
        TreeNode treeNode32 = new TreeNode(3);
        TreeNode treeNode33 = new TreeNode(-2);
        TreeNode treeNode41 = new TreeNode(-1);

        treeNode1.left = treeNode21;
        treeNode1.right = treeNode22;

        treeNode21.left = treeNode31;
        treeNode21.right = treeNode32;

        treeNode22.left = treeNode33;

        treeNode31.left = treeNode41;

        PathSum pathSum = new PathSum();
        Assertions.assertTrue(pathSum.hasPathSum(treeNode1, -1));
    }
}
