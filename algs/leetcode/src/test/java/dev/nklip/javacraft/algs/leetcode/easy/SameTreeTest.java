package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SameTreeTest {

    @Test
    void testCase1() {
        SameTree sameTree = new SameTree();

        TreeNode p1 = new TreeNode(1, new TreeNode(2), new TreeNode(3));
        TreeNode q1 = new TreeNode(1, new TreeNode(2), new TreeNode(3));

        Assertions.assertTrue(sameTree.isSameTree(p1, q1));
    }

    @Test
    void testCase2() {
        SameTree sameTree = new SameTree();

        TreeNode p1 = new TreeNode(1, new TreeNode(2), null);
        TreeNode q1 = new TreeNode(1, null, new TreeNode(2));

        Assertions.assertFalse(sameTree.isSameTree(p1, q1));
    }

    @Test
    void testCase3() {
        SameTree sameTree = new SameTree();

        TreeNode p1 = new TreeNode(1, new TreeNode(2), new TreeNode(1));
        TreeNode q1 = new TreeNode(1, new TreeNode(1), new TreeNode(2));

        Assertions.assertFalse(sameTree.isSameTree(p1, q1));
    }

}
