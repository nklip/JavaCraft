package dev.nklip.javacraft.algs.leetcode.easy;

import dev.nklip.javacraft.algs.leetcode.model.ListNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MergeTwoSortedListsTest {

    @Test
    void testCase1() {
        MergeTwoSortedLists solution = new MergeTwoSortedLists();

//   *****         *****         *****
// *       *     *       *     *       *
// *   1   * --> *   2   * --> *   4   *
// *       *     *       *     *       *
//   *****         *****         *****
        ListNode listNode14 = new ListNode(4);
        ListNode listNode12 = new ListNode(2, listNode14);
        ListNode listNode11 = new ListNode(1, listNode12);

//   *****         *****         *****
// *       *     *       *     *       *
// *   1   * --> *   3   * --> *   4   *
// *       *     *       *     *       *
//   *****         *****         *****
        ListNode listNode24 = new ListNode(4);
        ListNode listNode23 = new ListNode(3, listNode24);
        ListNode listNode21 = new ListNode(1, listNode23);

        ListNode actualResult = solution.mergeTwoLists(listNode11, listNode21);
        Assertions.assertEquals(1, actualResult.val);
        Assertions.assertEquals(1, actualResult.next.val);
        Assertions.assertEquals(2, actualResult.next.next.val);
        Assertions.assertEquals(3, actualResult.next.next.next.val);
        Assertions.assertEquals(4, actualResult.next.next.next.next.val);
        Assertions.assertEquals(4, actualResult.next.next.next.next.next.val);
    }

    @Test
    void testCase2() {
        MergeTwoSortedLists solution = new MergeTwoSortedLists();

        ListNode actualResult = solution.mergeTwoLists(null, null);
        Assertions.assertNull(actualResult);
    }

    @Test
    void testCase3() {
        MergeTwoSortedLists solution = new MergeTwoSortedLists();

        ListNode listNode21 = new ListNode(0);

        ListNode actualResult = solution.mergeTwoLists(null, listNode21);
        Assertions.assertNull(actualResult.next);
        Assertions.assertEquals(0, actualResult.val);
    }

    @Test
    void testCase4() {
        MergeTwoSortedLists solution = new MergeTwoSortedLists();

        // [-10,-9,-6,-4,1,9,9]
        ListNode listNode17 = new ListNode(9);
        ListNode listNode16 = new ListNode(9, listNode17);
        ListNode listNode15 = new ListNode(1, listNode16);
        ListNode listNode14 = new ListNode(-4, listNode15);
        ListNode listNode13 = new ListNode(-6, listNode14);
        ListNode listNode12 = new ListNode(-9, listNode13);
        ListNode listNode11 = new ListNode(-10, listNode12);

        // [-5,-3,0,7,8,8]
        ListNode listNode26 = new ListNode(8);
        ListNode listNode25 = new ListNode(8, listNode26);
        ListNode listNode24 = new ListNode(7, listNode25);
        ListNode listNode23 = new ListNode(0, listNode24);
        ListNode listNode22 = new ListNode(-3, listNode23);
        ListNode listNode21 = new ListNode(-5, listNode22);

        ListNode actualResult = solution.mergeTwoLists(listNode11, listNode21);

        // [-10,-9,-6,-5,-4,-3,0,1,7,8,8,9,9]
        Assertions.assertEquals(-10, actualResult.val);
        Assertions.assertEquals(-9,  actualResult.next.val);
        Assertions.assertEquals(-6,  actualResult.next.next.val);
        Assertions.assertEquals(-5,  actualResult.next.next.next.val);
        Assertions.assertEquals(-4,  actualResult.next.next.next.next.val);
        Assertions.assertEquals(-3,  actualResult.next.next.next.next.next.val);
        Assertions.assertEquals(0,   actualResult.next.next.next.next.next.next.val);
        Assertions.assertEquals(1,   actualResult.next.next.next.next.next.next.next.val);
        Assertions.assertEquals(7,   actualResult.next.next.next.next.next.next.next.next.val);
        Assertions.assertEquals(8,   actualResult.next.next.next.next.next.next.next.next.next.val);
        Assertions.assertEquals(8,   actualResult.next.next.next.next.next.next.next.next.next.next.val);
        Assertions.assertEquals(9,   actualResult.next.next.next.next.next.next.next.next.next.next.next.val);
        Assertions.assertEquals(9,   actualResult.next.next.next.next.next.next.next.next.next.next.next.next.val);
    }






}
