package dev.nklip.javacraft.algs.leetcode.medium;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;

/*
 * 2. Add Two Numbers
 *
 * LeetCode: https://leetcode.com/problems/add-two-numbers/
 *
 * You are given two non-empty linked lists representing two non-negative integers.
 * The digits are stored in reverse order, and each of their nodes contains a single digit.
 * Add the two numbers and return the sum as a linked list.
 *
 * You may assume the two numbers do not contain any leading zero, except the number 0 itself.
 */
public class AddTwoNumbers {

    // best runtime - beats 99.96%
    public ListNode addTwoNumbers(ListNode l1, ListNode l2) {
        ListNode root = null;
        ListNode last = null;

        int carry = 0;
        while (l1 != null || l2 != null) {
            int summary =
                    ((l1 == null) ? 0 : l1.val) +
                    ((l2 == null) ? 0 : l2.val) + carry;
            if (summary >= 10) {
                summary = summary - 10;
                carry = 1;
            } else {
                carry = 0;
            }
            ListNode sumNode = new ListNode(summary);

            if (root == null)  {
                root = sumNode;
                last = sumNode;
            } else {
                last.next = sumNode;
                last = sumNode;
            }

            if (l1 != null) {
                l1 = l1.next;
            }

            if (l2 != null) {
                l2 = l2.next;
            }
        }
        if (carry > 0) {
            last.next = new ListNode(carry);
        }
        return root;
    }

}
