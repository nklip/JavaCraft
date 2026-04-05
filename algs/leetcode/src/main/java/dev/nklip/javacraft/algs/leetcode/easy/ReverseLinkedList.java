package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;

/*
 * 206. Reverse Linked List
 *
 * LeetCode: https://leetcode.com/problems/reverse-linked-list
 *
 * Given the head of a singly linked list, reverse the list, and return the reversed list.
 */
public class ReverseLinkedList {
    public ListNode reverseList(ListNode head) {
        ListNode temp;
        ListNode prev = null;
        ListNode curr = head;
        while (curr != null) {
            temp = curr.next;
            curr.next = prev;
            prev = curr;
            curr = temp;
        }
        return prev;
    }

    public ListNode reverseListRecursive(ListNode head) {
        if (head == null || head.next == null) {
            return head;
        }
        ListNode deepestNode = reverseListRecursive(head, head.next);
        head.next = null;
        return deepestNode;
    }

    public ListNode reverseListRecursive(ListNode prev, ListNode next) {
        if (next.next == null) {
            next.next = prev;
            // the deepest node we should return first, i.e. 5
            return next;
        }

        ListNode tempNext = next.next;

        next.next = prev;

        return reverseListRecursive(next, tempNext);
    }
}
