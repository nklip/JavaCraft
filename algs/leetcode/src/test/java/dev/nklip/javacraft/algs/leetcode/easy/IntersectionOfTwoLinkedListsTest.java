package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class IntersectionOfTwoLinkedListsTest {

    /**
     * Input: intersectVal = 8, listA = [4,3,8,4,5], listB = [2,6,9,8,4,5], skipA = 2, skipB = 3
     * Output: Intersected at '8'
     * Explanation: The intersected node's value is 8 (note that this must not be 0 if the two lists intersect).
     * From the head of A, it reads as [4,3,8,4,5]. From the head of B, it reads as [2,6,9,8,4,5].
     * There are 2 nodes before the intersected node in A;
     * There are 3 nodes before the intersected node in B.
     * - Note that the intersected node's value is not 1 because the nodes with value 1 in A and B (2nd node in A and 3rd node in B) are different node references.
     * In other words, they point to two different locations in memory, while the nodes with value 8 in A and B (3rd node in A and 4th node in B) point to the same location in memory.
     * <p>
     * A:     4 → 3
     *              ↘
     *                8 → 4 → 5
     *              ↗
     * B: 2 → 6 → 9
     */
    @Test
    void testCase1() {
        ListNode nA1 = new ListNode(4);
        ListNode nA2 = new ListNode(3);
        ListNode nA3 = new ListNode(8);
        ListNode nA4 = new ListNode(4);
        ListNode nA5 = new ListNode(5);

        nA1.next = nA2;
        nA2.next = nA3;
        nA3.next = nA4;
        nA4.next = nA5;

        ListNode nB1 = new ListNode(2);
        ListNode nB2 = new ListNode(6);
        ListNode nB3 = new ListNode(9);

        nB1.next = nB2;
        nB2.next = nB3;
        nB3.next = nA3;

        IntersectionOfTwoLinkedLists solution = new IntersectionOfTwoLinkedLists();

        Assertions.assertEquals(8, solution.getIntersectionNode(nA1, nB1).val);
        Assertions.assertEquals(8, solution.getIntersectionNodeUseSet(nA1, nB1).val);
    }

    /**
     * Input: intersectVal = 2, listA = [1,9,1,2,4], listB = [3,2,4], skipA = 3, skipB = 1
     * Output: Intersected at '2'
     * Explanation: The intersected node's value is 2 (note that this must not be 0 if the two lists intersect).
     * From the head of A, it reads as [1,9,1,2,4].
     * From the head of B, it reads as [3,2,4].
     * There are 3 nodes before the intersected node in A;
     * There are 1 node before the intersected node in B.
     * <p>
     * A: 1 → 9 → 1
     *              ↘
     *                2 → 4
     *              ↗
     * B:        3
     */
    @Test
    void testCase2() {
        ListNode nA1 = new ListNode(1);
        ListNode nA2 = new ListNode(9);
        ListNode nA3 = new ListNode(1);
        ListNode nA4 = new ListNode(2);
        ListNode nA5 = new ListNode(4);

        nA1.next = nA2;
        nA2.next = nA3;
        nA3.next = nA4;
        nA4.next = nA5;

        ListNode nB1 = new ListNode(3);

        nB1.next = nA4;

        IntersectionOfTwoLinkedLists solution = new IntersectionOfTwoLinkedLists();

        Assertions.assertEquals(2, solution.getIntersectionNode(nA1, nB1).val);
        Assertions.assertEquals(2, solution.getIntersectionNodeUseSet(nA1, nB1).val);
    }

    /**
     * Input: intersectVal = 0, listA = [2,6,4], listB = [1,5], skipA = 3, skipB = 2
     * Output: No intersection
     * Explanation: From the head of A, it reads as [2,6,4].
     * From the head of B, it reads as [1,5].
     * Since the two lists do not intersect, intersectVal must be 0, while skipA and skipB can be arbitrary values.
     * Explanation: The two lists do not intersect, so return null.
     * <p>
     * A: 2 → 6 → 4
     * <p>
     * B: 1 → 5
     */
    @Test
    void testCase3() {
        ListNode nA1 = new ListNode(2);
        ListNode nA2 = new ListNode(6);
        ListNode nA3 = new ListNode(4);

        nA1.next = nA2;
        nA2.next = nA3;

        ListNode nB1 = new ListNode(1);

        nB1.next = new ListNode(5);

        IntersectionOfTwoLinkedLists solution = new IntersectionOfTwoLinkedLists();

        Assertions.assertNull(solution.getIntersectionNode(nA1, nB1));
        Assertions.assertNull(solution.getIntersectionNodeUseSet(nA1, nB1));
    }

    @Test
    void testCase4() {
        ListNode nA1 = new ListNode(1);

        IntersectionOfTwoLinkedLists solution = new IntersectionOfTwoLinkedLists();

        Assertions.assertEquals(1, solution.getIntersectionNode(nA1, nA1).val);
        Assertions.assertEquals(1, solution.getIntersectionNodeUseSet(nA1, nA1).val);
    }

    @Test
    void testCase5() {
        ListNode nA1 = new ListNode(3);

        ListNode nB1 = new ListNode(2);

        nB1.next = nA1;

        IntersectionOfTwoLinkedLists solution = new IntersectionOfTwoLinkedLists();

        Assertions.assertEquals(3, solution.getIntersectionNode(nA1, nB1).val);
        Assertions.assertEquals(3, solution.getIntersectionNodeUseSet(nA1, nB1).val);
    }
}
