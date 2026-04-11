package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PalindromeLinkedListTest {

    @Test
    public void testCase1() {
        ListNode node1 = new ListNode(1);
        ListNode node2 = new ListNode(2);
        ListNode node3 = new ListNode(2);
        ListNode node4 = new ListNode(1);

        node1.next = node2;
        node2.next = node3;
        node3.next = node4;

        PalindromeLinkedList solution = new PalindromeLinkedList();

        Assertions.assertTrue(solution.isPalindrome(node1));
    }

    @Test
    public void testCase2() {
        ListNode node1 = new ListNode(1);

        node1.next = new ListNode(2);

        PalindromeLinkedList solution = new PalindromeLinkedList();

        Assertions.assertFalse(solution.isPalindrome(node1));
    }
}
