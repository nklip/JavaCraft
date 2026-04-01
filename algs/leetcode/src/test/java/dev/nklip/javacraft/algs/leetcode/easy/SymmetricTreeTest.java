package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SymmetricTreeTest {

    /**
     *         1
     *       /   \
     *      2     2
     *     / \   / \
     *    3   4 4   3
     */
    @Test
    void testCase1() {
        SymmetricTree solution = new SymmetricTree();

        TreeNode node1 = new TreeNode(1);
        TreeNode nodeLeft2 = new TreeNode(2);
        TreeNode nodeLeft3 = new TreeNode(3);
        TreeNode nodeLeft4 = new TreeNode(4);
        TreeNode nodeRight2 = new TreeNode(2);
        TreeNode nodeRight3 = new TreeNode(3);
        TreeNode nodeRight4 = new TreeNode(4);

        node1.left = nodeLeft2;
        node1.right = nodeRight2;

        nodeLeft2.left = nodeLeft3;
        nodeLeft2.right = nodeLeft4;

        nodeRight2.left = nodeRight4;
        nodeRight2.right = nodeRight3;

        // root = [1,2,2,3,4,4,3]
        Assertions.assertTrue(solution.isSymmetric(node1));
        Assertions.assertTrue(solution.isSymmetricIterative(node1));
    }

    /**
     *         1
     *       /   \
     *      2     2
     *       \     \
     *        3     3
     */
    @Test
    void testCase2() {
        SymmetricTree solution = new SymmetricTree();

        TreeNode node1 = new TreeNode(1);
        TreeNode nodeLeft2 = new TreeNode(2);
        TreeNode nodeLeft3 = new TreeNode(3);

        TreeNode nodeRight2 = new TreeNode(2);
        TreeNode nodeRight3 = new TreeNode(3);

        node1.left = nodeLeft2;
        node1.right = nodeRight2;

        nodeLeft2.right = nodeLeft3;

        nodeRight2.right = nodeRight3;

        // [1,2,2,null,3,null,3]
        Assertions.assertFalse(solution.isSymmetric(node1));
        Assertions.assertFalse(solution.isSymmetricIterative(node1));
    }

    /**
     *         1
     *       /   \
     *      2     2
     *     /     /
     *    2     2
     */
    @Test
    void testCase3() {
        SymmetricTree solution = new SymmetricTree();

        TreeNode node1 = new TreeNode(1);
        TreeNode nodeLeft2 = new TreeNode(2);
        TreeNode nodeLeft22 = new TreeNode(2);

        TreeNode nodeRight2 = new TreeNode(2);
        TreeNode nodeRight22 = new TreeNode(2);

        node1.left = nodeLeft2;
        node1.right = nodeRight2;

        nodeLeft2.left = nodeLeft22;

        nodeRight2.left = nodeRight22;

        // [1,2,2,2,null,2]
        Assertions.assertFalse(solution.isSymmetric(node1));
        Assertions.assertFalse(solution.isSymmetricIterative(node1));
    }
}
