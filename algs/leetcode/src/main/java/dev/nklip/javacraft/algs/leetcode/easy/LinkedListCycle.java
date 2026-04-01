package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.*;
import dev.nklip.javacraft.algs.leetcode.model.ListNode;

/**
 * 141. Linked List Cycle
 * <p>
 * Given head, the head of a linked list, determine if the linked list has a cycle in it.
 * <p>
 * There is a cycle in a linked list if there is some node in the list that can be reached again by continuously following the next pointer.
 * Internally, pos is used to denote the index of the node that tail's next pointer is connected to.
 * Note that pos is not passed as a parameter.
 * <p>
 * Return true if there is a cycle in the linked list. Otherwise, return false.
 */
public class LinkedListCycle {

    // algorithm - Floyd's Tortoise & Hare cycle-finding algorithm.
    // time complexity - O (n log n)

    /**
     * Algorithm - Floyd's Tortoise & Hare cycle-finding algorithm.
     * <p>
     * The trick is called Two Pointers.
     * You need to introduce a second pointer.
     * The first one is your default iterator that moves by 1 step at once.
     * It is your slow tortoise.
     * And the second one is your hare.
     * It moves by 2 steps at once.
     * Once they are in the same place again you can say, YES, it is cycled list
     * <p>
     * time complexity - O(n); space complexity - O(1)
     */
    public boolean hasCycle(ListNode head) {
        int pos = -1;
        boolean hasCycle = false;
        if (head == null) {
            return hasCycle;
        }

        ListNode tortoise, hare;
        tortoise = hare = head;

        // detect the cycle
        while (hare.next != null && hare.next.next != null) {
            tortoise = tortoise.next;
            hare = hare.next.next;

            if (tortoise == hare) {
                hasCycle = true;

                pos = 0;
                tortoise = head;

                // Find start of cycle and count offset
                //
                // After the cycle is detected:
                //
                // 1) Move slow back to head
                // 2) Move both pointers 1 step at a time
                // 3) Count steps until they meet again
                while (tortoise != hare) {
                    tortoise = tortoise.next;
                    hare = hare.next;
                    pos++;
                }
                break;
            }
        }
        System.out.println("pos = " + pos);
        return hasCycle;
    }

    // time complexity - O (n log n)
    public boolean hasCycle2(ListNode head) {
        int pos = -1;
        boolean hasCycle = false;

        if (head != null) {
            Map<ListNode, Integer> linkedListNodes = new LinkedHashMap<>();
            linkedListNodes.put(head, ++pos);

            while (head.next != null) {
                head = head.next;
                if (linkedListNodes.containsKey(head.next)) {
                    pos = linkedListNodes.get(head.next);
                    hasCycle = true;
                    break;
                } else {
                    linkedListNodes.put(head.next, ++pos);
                }
            }
        }
        if (!hasCycle) {
            pos = -1;
        }
        System.out.println("pos = " + pos);
        return hasCycle;
    }

}
