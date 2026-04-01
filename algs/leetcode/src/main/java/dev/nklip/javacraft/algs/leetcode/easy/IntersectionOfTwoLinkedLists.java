package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.LinkedHashSet;
import java.util.Set;
import dev.nklip.javacraft.algs.leetcode.model.ListNode;

/**
 * 160. Intersection of Two Linked Lists
 * <p>
 * Given the heads of two singly linked-lists headA and headB, return the node at which the two lists intersect.
 * If the two linked lists have no intersection at all, return null.
 * <p>
 * For example, the following two linked lists begin to intersect at node 8:
 * <p>
 * A: 4 → 1
 *          ↘
 *            8 → 4 → 5
 *          ↗
 * B: 5 → 6 → 1
 * <p>
 * The test cases are generated such that there are no cycles anywhere in the entire linked structure.
 * <p>
 * Note that the linked lists must retain their original structure after the function returns.
 * <p>
 * Custom Judge:
 * <p>
 * The inputs to the judge are given as follows (your program is not given these inputs):
 * <p>
 * intersectVal - The value of the node where the intersection occurs. This is 0 if there is no intersected node.
 * listA - The first linked list.
 * listB - The second linked list.
 * skipA - The number of nodes to skip ahead in listA (starting from the head) to get to the intersected node.
 * skipB - The number of nodes to skip ahead in listB (starting from the head) to get to the intersected node.
 * The judge will then create the linked structure based on these inputs and pass the two heads, headA and headB to your program.
 * If you correctly return the intersected node, then your solution will be accepted.
 */
public class IntersectionOfTwoLinkedLists {

    // Time: O(m + n) average case
    // Space: O(1)
    public ListNode getIntersectionNode(ListNode headA, ListNode headB) {
        // boundary check
        if (headA == null || headB == null) {
            return null;
        }

        ListNode a = headA;
        ListNode b = headB;

        // if a & b have different len, then we will stop the loop after second iteration
        // for example:
        //     A:     4 → 3
        //                  ↘
        //                    8 → 4 → 5
        //                  ↗
        //     B: 2 → 6 → 9
        // a : 4 -> 3 -> 8 -> 4 -> 5 -> 2 -> 6 -> 9 -> 8 -> 4 -> 5 -> null
        // b : 2 -> 6 -> 9 -> 8 -> 4 -> 5 -> 4 -> 3 -> 8 -> 4 -> 5 -> null
        while (a != b) {
            // for the end of first iteration, we just reset the pointer to the head of another linkedlist
            a = a == null ? headB : a.next;
            b = b == null ? headA : b.next;
        }

        return a;
    }

    // Time: O(m + n) average case
    // Space: O(m)
    public ListNode getIntersectionNodeUseSet(ListNode headA, ListNode headB) {
        Set<ListNode> listA = new LinkedHashSet<>();

        // m
        while (headA != null) {
            listA.add(headA);
            headA = headA.next;
        }

        ListNode intersectStart = null;
        // n
        while (headB != null) {
            // contains O(1) - average time
            if (listA.contains(headB)) {
                intersectStart = headB;
                break;
            }
            headB = headB.next;
        }
        return intersectStart;
    }

}
