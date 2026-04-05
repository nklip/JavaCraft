package dev.nklip.javacraft.algs.leetcode.medium;

import dev.nklip.javacraft.algs.leetcode.easy.ReverseLinkedList;
import dev.nklip.javacraft.algs.leetcode.model.ListNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ReverseLinkedListTest {

    @Test
    void testCase1() {
        ListNode node1 = new ListNode(1);
        ListNode node2 = new ListNode(2);
        ListNode node3 = new ListNode(3);
        ListNode node4 = new ListNode(4);
        ListNode node5 = new ListNode(5);

        node1.next = node2;
        node2.next = node3;
        node3.next = node4;
        node4.next = node5;

        ReverseLinkedList solution = new ReverseLinkedList();

        ListNode actualNode = solution.reverseList(node1);
        Assertions.assertEquals(5, actualNode.val);
        Assertions.assertEquals(4, actualNode.next.val);
        Assertions.assertEquals(3, actualNode.next.next.val);
        Assertions.assertEquals(2, actualNode.next.next.next.val);
        Assertions.assertEquals(1, actualNode.next.next.next.next.val);

        ListNode nodeR1 = new ListNode(1);
        ListNode nodeR2 = new ListNode(2);
        ListNode nodeR3 = new ListNode(3);
        ListNode nodeR4 = new ListNode(4);
        ListNode nodeR5 = new ListNode(5);

        nodeR1.next = nodeR2;
        nodeR2.next = nodeR3;
        nodeR3.next = nodeR4;
        nodeR4.next = nodeR5;

        ListNode actualRecursiveNode = solution.reverseListRecursive(nodeR1);
        Assertions.assertEquals(5, actualRecursiveNode.val);
        Assertions.assertEquals(4, actualRecursiveNode.next.val);
        Assertions.assertEquals(3, actualRecursiveNode.next.next.val);
        Assertions.assertEquals(2, actualRecursiveNode.next.next.next.val);
        Assertions.assertEquals(1, actualRecursiveNode.next.next.next.next.val);
    }

    @Test
    void testCase2() {
        ListNode node1 = new ListNode(1);

        node1.next = new ListNode(2);

        ReverseLinkedList solution = new ReverseLinkedList();

        ListNode actualNode = solution.reverseList(node1);
        Assertions.assertEquals(2, actualNode.val);
        Assertions.assertEquals(1, actualNode.next.val);

        ListNode nodeR1 = new ListNode(1);

        nodeR1.next = new ListNode(2);

        ListNode actualRecursiveNode = solution.reverseListRecursive(nodeR1);
        Assertions.assertEquals(2, actualRecursiveNode.val);
        Assertions.assertEquals(1, actualRecursiveNode.next.val);
    }

    @Test
    void testCase3() {
        ReverseLinkedList solution = new ReverseLinkedList();

        ListNode actualNode = solution.reverseList(null);
        Assertions.assertNull(actualNode);
    }

    @Test
    void testCase4() {
        ListNode node1 = new ListNode(1);

        ReverseLinkedList solution = new ReverseLinkedList();

        ListNode actualNode = solution.reverseList(node1);
        Assertions.assertEquals(1, actualNode.val);
    }
}
