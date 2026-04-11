package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/*
 * 234. Palindrome Linked List
 *
 * LeetCode: https://leetcode.com/problems/palindrome-linked-list/
 *
 * Given the head of a singly linked list, return true if it is a palindrome or false otherwise.
 */
public class PalindromeLinkedList {
    public boolean isPalindrome(ListNode head) {
        List<Integer> charList = new ArrayList<>();

        while (head != null) {
            charList.add(head.val);
            head = head.next;
        }

        for (int i = 0, y = charList.size() - 1; i < charList.size(); i++, y--) {
            if (!Objects.equals(charList.get(i), charList.get(y))) {
                return false;
            }
            if (i > y) {
                return true;
            }
        }
        return true;
    }
}
