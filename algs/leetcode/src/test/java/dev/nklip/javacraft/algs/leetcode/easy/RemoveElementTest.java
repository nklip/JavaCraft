package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RemoveElementTest {

    /**
     * Explanation: Your function should return k = 2, with the first two elements of nums being 2.
     * It does not matter what you leave beyond the returned k (hence they are underscores).
     */
    @Test
    void testCase1() {
        RemoveElement solution = new RemoveElement();
        int []actualNums = {3,2,2,3};
        Assertions.assertEquals(2, solution.removeElement(actualNums, 3));
    }

    /**
     * Explanation: Your function should return k = 5, with the first five elements of nums containing 0, 0, 1, 3, and 4.
     * Note that the five elements can be returned in any order.
     * It does not matter what you leave beyond the returned k (hence they are underscores).
     */
    @Test
    void testCase2() {
        RemoveElement solution = new RemoveElement();
        int []actualNums = {0,1,2,2,3,0,4,2};
        Assertions.assertEquals(5, solution.removeElement(actualNums, 2));
    }
}
