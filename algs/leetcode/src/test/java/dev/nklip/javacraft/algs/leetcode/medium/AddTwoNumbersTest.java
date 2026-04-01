package dev.nklip.javacraft.algs.leetcode.medium;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AddTwoNumbersTest {
    @Test
    void testCase1() {
        ListNode node1 = new ListNode(2);
        ListNode node2 = new ListNode(4);
        ListNode node3 = new ListNode(3);
        node1.next = node2;
        node2.next = node3;

        ListNode node4 = new ListNode(5);
        ListNode node5 = new ListNode(6);
        ListNode node6 = new ListNode(4);
        node4.next = node5;
        node5.next = node6;

        //   2 → 4 → 3
        // + 5 → 6 → 4
        // -----------
        //   7 → 0 → 8
        //
        // Step-by-step:
        // 2 + 5 = 7
        // 4 + 6 = 10 → write 0, carry 1
        // 3 + 4 + 1 = 8
        AddTwoNumbers solution = new AddTwoNumbers();

        ListNode actual = solution.addTwoNumbers(node1, node4);
        Assertions.assertEquals(7, actual.val);
        Assertions.assertEquals(0, actual.next.val);
        Assertions.assertEquals(8, actual.next.next.val);
    }

    @Test
    void testCase2() {
        ListNode node1 = new ListNode(0);
        ListNode node4 = new ListNode(0);

        AddTwoNumbers solution = new AddTwoNumbers();
        ListNode actual = solution.addTwoNumbers(node1, node4);
        Assertions.assertEquals(0, actual.val);
    }

    @Test
    void testCase3() {
        ListNode node1 = new ListNode(9);
        ListNode node2 = new ListNode(9);
        ListNode node3 = new ListNode(9);
        ListNode node4 = new ListNode(9);
        ListNode node5 = new ListNode(9);
        ListNode node6 = new ListNode(9);
        ListNode node7 = new ListNode(9);
        node1.next = node2;
        node2.next = node3;
        node3.next = node4;
        node4.next = node5;
        node5.next = node6;
        node6.next = node7;

        ListNode node9 = new ListNode(9);
        ListNode node10 = new ListNode(9);
        ListNode node11 = new ListNode(9);
        ListNode node12 = new ListNode(9);
        node9.next = node10;
        node10.next = node11;
        node11.next = node12;

        AddTwoNumbers solution = new AddTwoNumbers();

        ListNode actual = solution.addTwoNumbers(node1, node9);
        Assertions.assertEquals(8, actual.val);
        Assertions.assertEquals(9, actual.next.val);
        Assertions.assertEquals(9, actual.next.next.val);
        Assertions.assertEquals(9, actual.next.next.next.val);
        Assertions.assertEquals(0, actual.next.next.next.next.val);
        Assertions.assertEquals(0, actual.next.next.next.next.next.val);
        Assertions.assertEquals(0, actual.next.next.next.next.next.next.val);
        Assertions.assertEquals(1, actual.next.next.next.next.next.next.next.val);
    }
}
