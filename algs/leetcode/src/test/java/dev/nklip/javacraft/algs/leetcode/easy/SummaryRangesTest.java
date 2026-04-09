package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SummaryRangesTest {

    @Test
    void testCase1() {
        SummaryRanges solution = new SummaryRanges();

        int[] nums = new int[] {0,1,2,4,5,7};
        Assertions.assertEquals(
                "[0->2, 4->5, 7]",
                solution.summaryRanges(nums).toString()
        );
    }

    @Test
    void testCase2() {
        SummaryRanges solution = new SummaryRanges();

        int[] nums = new int[] {0,2,3,4,6,8,9};
        Assertions.assertEquals(
                "[0, 2->4, 6, 8->9]",
                solution.summaryRanges(nums).toString()
        );
    }

    @Test
    void testCase3() {
        SummaryRanges solution = new SummaryRanges();

        int[] nums = new int[] {-1};
        Assertions.assertEquals(
                "[-1]",
                solution.summaryRanges(nums).toString()
        );
    }

}
