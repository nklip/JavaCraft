package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;

/*
 * 203. Remove Linked List Elements
 *
 * LeetCode: https://leetcode.com/problems/remove-linked-list-elements/
 *
 * Given the head of a linked list and an integer val,
 * remove all the nodes of the linked list that has Node.val == val, and return the new head.
 */
public class RemoveLinkedListElements {
    public ListNode removeElements(ListNode head, int val) {
        if (head == null) {
            return null;
        }
        ListNode returnNode = null;
        ListNode current = null;
        while (head != null) {
            ListNode temp = head.next;
            if (head.val != val) {
                if (returnNode == null) {
                    returnNode = head;
                    head.next = null;
                    current = returnNode;
                } else {
                    current.next = head;
                    head.next = null;
                    current = head;
                }
            }
            head = temp;
        }
        return returnNode;
    }
}
