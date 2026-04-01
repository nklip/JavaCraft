package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BinaryTreeInorderTraversalTest {

    /**
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
     */
    @Test
    void testCase1() {
        BinaryTreeInorderTraversal solution = new BinaryTreeInorderTraversal();

        TreeNode node1 = new TreeNode(1);
        TreeNode node2 = new TreeNode(2);
        TreeNode node3 = new TreeNode(3);

        node1.left = null;
        node1.right = node2;

        node2.left = node3;
        node2.right = null;
        Assertions.assertEquals("[1,3,2]",
                solution.inorderTraversal(node1)
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
        BinaryTreeInorderTraversal solution = new BinaryTreeInorderTraversal();

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
        Assertions.assertEquals("[4,2,6,5,7,1,3,9,8]",
                solution.inorderTraversal(node1)
                .toString()
                .replaceAll(" ", "")
        );
    }

    @Test
    void testCase3() {
        BinaryTreeInorderTraversal solution = new BinaryTreeInorderTraversal();

        TreeNode root = new TreeNode();

        Assertions.assertEquals("[0]",
                solution.inorderTraversal(root)
                .toString()
                .replaceAll(" ", "")
        );
    }

    @Test
    void testCase4() {
        BinaryTreeInorderTraversal solution = new BinaryTreeInorderTraversal();

        TreeNode root = new TreeNode(1);

        Assertions.assertEquals("[1]",
                solution.inorderTraversal(root)
                .toString()
                .replaceAll(" ", "")
        );
    }

    /**
     * Tree:
     *         4
     *        / \
     *       2   6
     *      / \ / \
     *     1  3 5  7
     * <p>
     * [1, 2, 3, 4, 5, 6, 7]
     */
    @Test
    void testCase5() {
        BinaryTreeInorderTraversal solution = new BinaryTreeInorderTraversal();

        TreeNode node1 = new TreeNode(1);
        TreeNode node3 = new TreeNode(3);
        TreeNode node5 = new TreeNode(5);
        TreeNode node7 = new TreeNode(7);

        TreeNode node6 = new TreeNode(6, node5, node7);
        TreeNode node2 = new TreeNode(2, node1, node3);
        TreeNode node4 = new TreeNode(4, node2, node6);

        Assertions.assertEquals("[1,2,3,4,5,6,7]",
                solution.inorderTraversalRecursive(node4)
                        .toString()
                        .replaceAll(" ", "")
        );
    }
}
