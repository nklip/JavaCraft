package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RemoveLinkedListElementsTest {
    @Test
    void testCase1() {
        ListNode listNode1 = new ListNode(1);
        ListNode listNode2 = new ListNode(2);
        ListNode listNode3 = new ListNode(6);
        ListNode listNode4 = new ListNode(3);
        ListNode listNode5 = new ListNode(4);
        ListNode listNode6 = new ListNode(5);
        ListNode listNode7 = new ListNode(6);

        listNode1.next = listNode2;
        listNode2.next = listNode3;
        listNode3.next = listNode4;
        listNode4.next = listNode5;
        listNode5.next = listNode6;
        listNode6.next = listNode7;

        RemoveLinkedListElements solution = new RemoveLinkedListElements();

        ListNode actualNode = solution.removeElements(listNode1, 6);
        Assertions.assertEquals(1, actualNode.val);
        Assertions.assertEquals(2, actualNode.next.val);
        Assertions.assertEquals(3, actualNode.next.next.val);
        Assertions.assertEquals(4, actualNode.next.next.next.val);
        Assertions.assertEquals(5, actualNode.next.next.next.next.val);
    }

    @Test
    void testCase2() {
        RemoveLinkedListElements solution = new RemoveLinkedListElements();

        ListNode actualNode = solution.removeElements(null, 1);
        Assertions.assertNull(actualNode);
    }

    @Test
    void testCase3() {
        ListNode listNode1 = new ListNode(7);
        ListNode listNode2 = new ListNode(7);
        ListNode listNode3 = new ListNode(7);
        ListNode listNode4 = new ListNode(7);

        listNode1.next = listNode2;
        listNode2.next = listNode3;
        listNode3.next = listNode4;

        RemoveLinkedListElements solution = new RemoveLinkedListElements();

        ListNode actualNode = solution.removeElements(listNode1, 7);
        Assertions.assertNull(actualNode);
    }
}
