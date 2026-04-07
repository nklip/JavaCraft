package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.TreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CountCompleteTreeNodesTest {

    @Test
    @SuppressWarnings("PointlessBitwiseExpression") // it's not pointless, it's demonstrational
    void testBitwise() {
        // 1 << leftDepth is the bitwise left shift operator.
        // It shifts the binary representation of 1 to the left by leftDepth positions,
        // effectively computing 2^leftDepth.
        //
        // Why use it instead of Math.pow
        //  1) It's an integer operation — Math.pow(2, n) returns double,
        //      which requires casting and can introduce floating-point imprecision.
        //  2) It's a single CPU instruction — bit shifts are one of the fastest operations
        //      a processor can execute.
        //      Math.pow is a function call with general-purpose floating-point math.
        //  3) It's idiomatic — in algorithms involving binary trees, powers of two,
        //      or bitmasks, << is the standard convention.
        Assertions.assertEquals(1, 1 << 0);
        Assertions.assertEquals(2, 1 << 1);
        Assertions.assertEquals(4, 1 << 2);
        Assertions.assertEquals(8, 1 << 3);
        Assertions.assertEquals(16, 1 << 4);

        // Because 2 is already 1 << 1, shifting it further by n gives a total shift of n + 1.
        // The general rule:
        // x << n  =  x * 2ⁿ
        Assertions.assertEquals(2, 2 << 0, "Same as 1 << (0 + 1)");
        Assertions.assertEquals(4, 2 << 1, "Same as 1 << (1 + 1)");
        Assertions.assertEquals(8, 2 << 2, "Same as 1 << (2 + 1)");
        Assertions.assertEquals(16, 2 << 3, "Same as 1 << (3 + 1)");
        Assertions.assertEquals(32, 2 << 4, "Same as 1 << (4 + 1)");
    }

    @Test
    void testCase1() {
        TreeNode node1 = new TreeNode(1);
        TreeNode node2 = new TreeNode(2);
        TreeNode node3 = new TreeNode(3);
        TreeNode node4 = new TreeNode(4);
        TreeNode node5 = new TreeNode(5);
        TreeNode node6 = new TreeNode(6);

        node1.left = node2;
        node1.right = node3;

        node2.left = node4;
        node2.right = node5;

        node3.left = node6;

        CountCompleteTreeNodes solution = new CountCompleteTreeNodes();

        Assertions.assertEquals(6, solution.countNodes(node1));
    }

    @Test
    void testCase2() {
        CountCompleteTreeNodes solution = new CountCompleteTreeNodes();

        Assertions.assertEquals(0, solution.countNodes(null));
    }

    @Test
    void testCase3() {
        TreeNode node1 = new TreeNode(1);

        CountCompleteTreeNodes solution = new CountCompleteTreeNodes();

        Assertions.assertEquals(1, solution.countNodes(node1));
    }

    @Test
    void testCase4() {
        TreeNode node1 = new TreeNode(1);
        node1.left = new TreeNode(2);

        CountCompleteTreeNodes solution = new CountCompleteTreeNodes();

        Assertions.assertEquals(2, solution.countNodes(node1));
    }

    @Test
    void testCase5() {
        TreeNode node1 = new TreeNode(1);
        node1.left = new TreeNode(2);
        node1.right = new TreeNode(3);

        CountCompleteTreeNodes solution = new CountCompleteTreeNodes();

        Assertions.assertEquals(3, solution.countNodes(node1));
    }

}
