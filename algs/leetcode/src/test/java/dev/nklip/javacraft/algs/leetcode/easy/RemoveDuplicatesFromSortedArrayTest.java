package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RemoveDuplicatesFromSortedArrayTest {

    /**
     * Explanation: Your function should return k = 2, with the first two elements of nums being 1 and 2 respectively.
     * It does not matter what you leave beyond the returned k (hence they are underscores).
     */
    @Test
    void testCase1() {
        RemoveDuplicatesFromSortedArray solution = new RemoveDuplicatesFromSortedArray();
        int []actualNums = {1,1,2};
        int []expectedNums = {1,2,2};

        Assertions.assertEquals(2, solution.removeDuplicates(actualNums));
        Assertions.assertArrayEquals(expectedNums, actualNums);
    }

    /**
     * Explanation: Your function should return k = 5, with the first five elements of nums being 0, 1, 2, 3, and 4 respectively.
     * It does not matter what you leave beyond the returned k (hence they are underscores).
     */
    @Test
    void testCase2() {
        RemoveDuplicatesFromSortedArray solution = new RemoveDuplicatesFromSortedArray();
        int []actualNums = {0,0,1,1,1,2,2,3,3,4};
        int []expectedNums = {0,1,2,3,4,2,2,3,3,4};

        Assertions.assertEquals(5, solution.removeDuplicates(actualNums));
        Assertions.assertArrayEquals(expectedNums, actualNums);
    }

}
