package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BinaryTreePreorderTraversalTest {

    /**
     *     1
     *      \
     *       2
     *      /
     *     3
     */
    @Test
    void testCase1() {
        TreeNode treeNode1 = new TreeNode(1);
        TreeNode treeNode2 = new TreeNode(2);
        TreeNode treeNode3 = new TreeNode(3);

        treeNode1.right = treeNode2;

        treeNode2.left = treeNode3;

        BinaryTreePreorderTraversal solution = new BinaryTreePreorderTraversal();

        Assertions.assertEquals("[1,2,3]",
                solution.preorderTraversal(treeNode1)
                        .toString()
                        .replaceAll(" ", "")
        );
        Assertions.assertEquals("[1,2,3]",
                solution.preorderTraversalRecursive(treeNode1)
                        .toString()
                        .replaceAll(" ", "")
        );
    }

    /**
     * Tree:
     *         1
     *       /   \
     *      2     3
     *     / \     \
     *    4   5     8
     *       / \   /
     *      6   7 9
     */
    @Test
    void testCase2() {
        TreeNode node1 = new TreeNode(1);
        TreeNode node2 = new TreeNode(2);
        TreeNode node3 = new TreeNode(3);
        TreeNode node4 = new TreeNode(4);
        TreeNode node5 = new TreeNode(5);
        TreeNode node6 = new TreeNode(6);
        TreeNode node7 = new TreeNode(7);
        TreeNode node8 = new TreeNode(8);
        TreeNode node9 = new TreeNode(9);

        node1.left = node2;
        node1.right = node3;

        node2.left = node4;
        node2.right = node5;

        node5.left = node6;
        node5.right = node7;

        node3.left = null;
        node3.right = node8;

        node8.left = node9;
        node8.right = null;

        BinaryTreePreorderTraversal solution = new BinaryTreePreorderTraversal();

        Assertions.assertEquals("[1,2,4,5,6,7,3,8,9]",
                solution.preorderTraversal(node1)
                        .toString()
                        .replaceAll(" ", "")
        );
        Assertions.assertEquals(
                "[1,2,4,5,6,7,3,8,9]",
                solution.preorderTraversalRecursive(node1)
                        .toString()
                        .replaceAll(" ", "")
        );
    }

    @Test
    void testCase3() {
        BinaryTreePreorderTraversal solution = new BinaryTreePreorderTraversal();

        Assertions.assertEquals("[]",
                solution.preorderTraversal(null)
                        .toString()
                        .replaceAll(" ", "")
        );
        Assertions.assertEquals("[]",
                solution.preorderTraversalRecursive(null)
                        .toString()
                        .replaceAll(" ", "")
        );
    }

    @Test
    void testCase4() {
        BinaryTreePreorderTraversal solution = new BinaryTreePreorderTraversal();

        TreeNode root = new TreeNode(1);

        Assertions.assertEquals("[1]",
                solution.preorderTraversal(root)
                        .toString()
                        .replaceAll(" ", "")
        );
        Assertions.assertEquals("[1]",
                solution.preorderTraversalRecursive(root)
                        .toString()
                        .replaceAll(" ", "")
        );
    }
}
