package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SearchInsertPositionTest {

    @Test
    void testCase01() {
        SearchInsertPosition solution = new SearchInsertPosition();
        int []nums = {1,3,5,6};
        Assertions.assertEquals(2, solution.searchInsert(nums, 5));
    }

    @Test
    void testCase02() {
        SearchInsertPosition solution = new SearchInsertPosition();
        int []nums = {1,3,5,6};
        Assertions.assertEquals(1, solution.searchInsert(nums, 2));
    }

    @Test
    void testCase03() {
        SearchInsertPosition solution = new SearchInsertPosition();
        int []nums = {1,3,5,6};
        Assertions.assertEquals(4, solution.searchInsert(nums, 7));
    }

    @Test
    void testCase04() {
        SearchInsertPosition solution = new SearchInsertPosition();
        int []nums = {1,3,5,6};
        Assertions.assertEquals(2, solution.searchInsert(nums, 4));
    }

    @Test
    void testCase05() {
        SearchInsertPosition solution = new SearchInsertPosition();
        int []nums = {1,3,5,6};
        Assertions.assertEquals(0, solution.searchInsert(nums, 0));
    }

    @Test
    void testCase06() {
        SearchInsertPosition solution = new SearchInsertPosition();
        int []nums = {1,3,4,5,6};
        Assertions.assertEquals(2, solution.searchInsert(nums, 4));
    }

}
