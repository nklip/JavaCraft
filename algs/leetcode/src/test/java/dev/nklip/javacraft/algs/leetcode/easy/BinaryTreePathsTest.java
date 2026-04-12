package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BinaryTreePathsTest {
    @Test
    public void testCase1() {
        //        1
        //      /   \
        //     2     3
        //      \
        //       5
        TreeNode node1 = new TreeNode(1);
        TreeNode node2 = new TreeNode(2);
        TreeNode node3 = new TreeNode(3);
        TreeNode node5 = new TreeNode(5);

        node1.left = node2;
        node1.right = node3;

        node2.right = node5;

        BinaryTreePaths solution = new BinaryTreePaths();

        List<String> paths = solution.binaryTreePaths(node1);
        Assertions.assertEquals("1->2->5", paths.get(1));
        Assertions.assertEquals("1->3", paths.get(0));
    }

    @Test
    public void testCase2() {
        TreeNode node1 = new TreeNode(1);

        BinaryTreePaths solution = new BinaryTreePaths();

        List<String> paths = solution.binaryTreePaths(node1);
        Assertions.assertEquals("1", paths.getFirst());
    }

    @Test
    public void testCase3() {
        //        1
        //      /   \
        //     2     3
        //    / \
        //   5   6
        TreeNode node1 = new TreeNode(1);
        TreeNode node2 = new TreeNode(2);
        TreeNode node3 = new TreeNode(3);
        TreeNode node5 = new TreeNode(5);
        TreeNode node6 = new TreeNode(6);

        node1.left = node2;
        node1.right = node3;

        node2.left = node5;
        node2.right = node6;

        BinaryTreePaths solution = new BinaryTreePaths();

        List<String> paths = solution.binaryTreePathsRecursive(node1);
        Assertions.assertEquals("1->2->5", paths.get(0));
        Assertions.assertEquals("1->2->6", paths.get(1));
        Assertions.assertEquals("1->3", paths.get(2));
    }
}
