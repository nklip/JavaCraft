package dev.nklip.javacraft.algs.leetcode.medium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SortColorsTest {

    @Test
    void testCase1() {
        int[] actualNums = new int[]{2,0,2,1,1,0};
        SortColors solution = new SortColors();

        solution.sortColors(actualNums);

        int[] expectedNums = new int[]{0,0,1,1,2,2};
        Assertions.assertArrayEquals(expectedNums, actualNums);
    }

    @Test
    void testCase2() {
        int[] actualNums = new int[]{2,0,1};
        SortColors solution = new SortColors();

        solution.sortColors(actualNums);

        int[] expectedNums = new int[]{0,1,2};
        Assertions.assertArrayEquals(expectedNums, actualNums);
    }
}
