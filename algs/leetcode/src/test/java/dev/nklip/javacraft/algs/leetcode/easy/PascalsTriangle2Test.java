package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PascalsTriangle2Test {
    @Test
    void testCase1() {
        List<Integer> expectedRow = new ArrayList<>();
        expectedRow.add(1);
        expectedRow.add(3);
        expectedRow.add(3);
        expectedRow.add(1);

        PascalsTriangle2 solution = new PascalsTriangle2();
        Assertions.assertEquals(expectedRow.toString(), solution.getRow(3).toString());
    }

    @Test
    void testCase2() {
        List<Integer> expectedRow = new ArrayList<>();
        expectedRow.add(1);

        PascalsTriangle2 solution = new PascalsTriangle2();
        Assertions.assertEquals(expectedRow.toString(), solution.getRow(0).toString());
    }

    @Test
    void testCase3() {
        List<Integer> expectedRow = new ArrayList<>();
        expectedRow.add(1);
        expectedRow.add(1);

        PascalsTriangle2 solution = new PascalsTriangle2();
        Assertions.assertEquals(expectedRow.toString(), solution.getRow(1).toString());
    }
}
