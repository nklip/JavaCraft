package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LinkedListCycleTest {

    @Test
    void testCase1() {
        ListNode listNode1 = new ListNode(3);
        ListNode listNode2 = new ListNode(2);
        ListNode listNode3 = new ListNode(0);
        ListNode listNode4 = new ListNode(-4);

        listNode1.next = listNode2;
        listNode2.next = listNode3;
        listNode3.next = listNode4;
        listNode4.next = listNode2;

        LinkedListCycle solution = new LinkedListCycle();

        // 3 → 2 → 0 → -4
        //     ↑        ↓
        //     └────────┘
        // Explanation: There is a cycle in the linked list, where the tail connects to the 1st node (0-indexed).
        Assertions.assertTrue(solution.hasCycle(listNode1));
        Assertions.assertTrue(solution.hasCycle2(listNode1));
    }

    @Test
    void testCase2() {
        ListNode listNode1 = new ListNode(1);
        ListNode listNode2 = new ListNode(2);

        listNode1.next = listNode2;
        listNode2.next = listNode1;

        LinkedListCycle solution = new LinkedListCycle();

        // 1 -→ 2
        // ↑    ↓
        // └────┘
        // Explanation: There is a cycle in the linked list, where the tail connects to the 0th node.
        Assertions.assertTrue(solution.hasCycle(listNode1));
        Assertions.assertTrue(solution.hasCycle2(listNode1));
    }

    @Test
    void testCase3() {
        ListNode listNode1 = new ListNode(1);

        LinkedListCycle solution = new LinkedListCycle();

        // Explanation: There is no cycle in the linked list.
        Assertions.assertFalse(solution.hasCycle(listNode1));
        Assertions.assertFalse(solution.hasCycle2(listNode1));
    }

    @Test
    void testCase4() {
        LinkedListCycle solution = new LinkedListCycle();

        // Explanation: There is no linked list.
        Assertions.assertFalse(solution.hasCycle(null));
        Assertions.assertFalse(solution.hasCycle2(null));
    }

    @Test
    void testCase5() {
        ListNode listNode1 = new ListNode(1);

        listNode1.next = new ListNode(2);

        LinkedListCycle solution = new LinkedListCycle();

        // 1 -→ 2
        // Explanation: There is no cycle in the linked list.
        Assertions.assertFalse(solution.hasCycle(listNode1));
        Assertions.assertFalse(solution.hasCycle2(listNode1));
    }

}
