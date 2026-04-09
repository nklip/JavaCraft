package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class InvertBinaryTreeTest {
    @Test
    public void testCase1() {
        //        4
        //      /   \
        //     2     7
        //    / \   / \
        //   1   3 6   9
        TreeNode node1 = new TreeNode(4);
        TreeNode node2 = new TreeNode(2);
        TreeNode node3 = new TreeNode(1);
        TreeNode node4 = new TreeNode(3);
        TreeNode node5 = new TreeNode(7);
        TreeNode node6 = new TreeNode(6);
        TreeNode node7 = new TreeNode(9);

        node1.left = node2;
        node1.right = node5;

        node2.left = node3;
        node2.right = node4;

        node5.left = node6;
        node5.right = node7;

        InvertBinaryTree solution = new InvertBinaryTree();

        //        4
        //      /   \
        //     7     2
        //    / \   / \
        //   9   6 3   1
        TreeNode actualNode = solution.invertTree(node1);
        Assertions.assertEquals(4, actualNode.val);
        TreeNode actualLeftNode = actualNode.left;
        TreeNode actualRightNode = actualNode.right;
        Assertions.assertEquals(7, actualLeftNode.val);
        Assertions.assertEquals(2, actualRightNode.val);

        Assertions.assertEquals(9, actualLeftNode.left.val);
        Assertions.assertEquals(6, actualLeftNode.right.val);

        Assertions.assertEquals(3, actualRightNode.left.val);
        Assertions.assertEquals(1, actualRightNode.right.val);

        TreeNode actualRNode = solution.invertTreeRecursive(node1);
        Assertions.assertEquals(4, actualRNode.val);
        TreeNode actualLeftRNode = actualRNode.left;
        TreeNode actualRightRNode = actualRNode.right;
        Assertions.assertEquals(7, actualLeftRNode.val);
        Assertions.assertEquals(2, actualRightRNode.val);

        Assertions.assertEquals(9, actualLeftRNode.left.val);
        Assertions.assertEquals(6, actualLeftRNode.right.val);

        Assertions.assertEquals(3, actualRightRNode.left.val);
        Assertions.assertEquals(1, actualRightRNode.right.val);
    }

    @Test
    public void testCase2() {
        TreeNode node1 = new TreeNode(2);
        TreeNode node2 = new TreeNode(1);
        TreeNode node3 = new TreeNode(3);

        node1.left = node2;
        node1.right = node3;

        InvertBinaryTree solution = new InvertBinaryTree();

        TreeNode actualNode = solution.invertTree(node1);

        Assertions.assertEquals(2, actualNode.val);
        Assertions.assertEquals(3, actualNode.left.val);
        Assertions.assertEquals(1, actualNode.right.val);

        TreeNode actualRNode = solution.invertTreeRecursive(node1);

        Assertions.assertEquals(2, actualRNode.val);
        Assertions.assertEquals(3, actualRNode.left.val);
        Assertions.assertEquals(1, actualRNode.right.val);
    }

    @Test
    public void testCase3() {
        InvertBinaryTree solution = new InvertBinaryTree();

        Assertions.assertNull(solution.invertTree(null));
        Assertions.assertNull(solution.invertTreeRecursive(null));
    }

    @Test
    public void testCase4() {
        //        4
        //      /   \
        //     2     7
        //    / \
        //   1   3
        TreeNode node1 = new TreeNode(4);
        TreeNode node2 = new TreeNode(2);
        TreeNode node3 = new TreeNode(1);
        TreeNode node4 = new TreeNode(3);
        TreeNode node5 = new TreeNode(7);

        node1.left = node2;
        node1.right = node5;

        node2.left = node3;
        node2.right = node4;

        InvertBinaryTree solution = new InvertBinaryTree();

        //        4
        //      /   \
        //     7     2
        //          / \
        //         3   1
        TreeNode actualNode = solution.invertTree(node1);
        Assertions.assertEquals(4, actualNode.val);
        TreeNode actualLeftNode = actualNode.left;
        TreeNode actualRightNode = actualNode.right;
        Assertions.assertEquals(7, actualLeftNode.val);
        Assertions.assertEquals(2, actualRightNode.val);

        Assertions.assertEquals(3, actualRightNode.left.val);
        Assertions.assertEquals(1, actualRightNode.right.val);

        TreeNode actualRNode = solution.invertTreeRecursive(node1);
        Assertions.assertEquals(4, actualRNode.val);
        TreeNode actualLeftRNode = actualRNode.left;
        TreeNode actualRightRNode = actualRNode.right;
        Assertions.assertEquals(7, actualLeftRNode.val);
        Assertions.assertEquals(2, actualRightRNode.val);

        Assertions.assertEquals(3, actualRightRNode.left.val);
        Assertions.assertEquals(1, actualRightRNode.right.val);
    }
}
