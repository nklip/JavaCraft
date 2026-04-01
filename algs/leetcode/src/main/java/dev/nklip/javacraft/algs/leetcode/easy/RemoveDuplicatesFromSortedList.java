package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;

/**
 * 83. Remove Duplicates from Sorted List
 * <p>
 * Given the head of a sorted linked list, delete all duplicates such that each element appears only once.
 * Return the linked list sorted as well.
 */
public class RemoveDuplicatesFromSortedList {

    public ListNode deleteDuplicates(ListNode head) {
        ListNode root = head;
        while (head != null && head.next != null) {
            if (head.val == head.next.val) {
                head.next = findNextValue(head.next, head.val);
            } else {
                head = head.next;
            }
        }
        return root;
    }

    ListNode findNextValue(ListNode head, int oldValue) {
        while (head.next != null) {
            head = head.next;

            if (head.val != oldValue) {
                return head;
            }
        }
        return head.next;
    }

}
