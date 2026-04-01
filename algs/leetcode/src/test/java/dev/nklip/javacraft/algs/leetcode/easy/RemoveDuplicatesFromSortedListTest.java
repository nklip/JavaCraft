package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RemoveDuplicatesFromSortedListTest {

    @Test
    void testCase1() {
        RemoveDuplicatesFromSortedList solution = new RemoveDuplicatesFromSortedList();

        ListNode listNode13 = new ListNode(2);
        ListNode listNode12 = new ListNode(1, listNode13);
        ListNode listNode11 = new ListNode(1, listNode12);

        ListNode actualResult = solution.deleteDuplicates(listNode11);
        Assertions.assertEquals(1, actualResult.val);
        Assertions.assertEquals(2, actualResult.next.val);
    }

    @Test
    void testCase2() {
        RemoveDuplicatesFromSortedList solution = new RemoveDuplicatesFromSortedList();

        ListNode listNode15 = new ListNode(3);
        ListNode listNode14 = new ListNode(3, listNode15);
        ListNode listNode13 = new ListNode(2, listNode14);
        ListNode listNode12 = new ListNode(1, listNode13);
        ListNode listNode11 = new ListNode(1, listNode12);

        ListNode actualResult = solution.deleteDuplicates(listNode11);
        Assertions.assertEquals(1, actualResult.val);
        Assertions.assertEquals(2, actualResult.next.val);
        Assertions.assertEquals(3, actualResult.next.next.val);
    }
}
