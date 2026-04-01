package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PascalsTriangleTest {

    @Test
    void testCase1() {
        List<List<Integer>> expectedResult = new ArrayList<>();
        List<Integer> row1 = new ArrayList<>();
        row1.add(1);
        expectedResult.add(row1);
        List<Integer> row2 = new ArrayList<>();
        row2.add(1);
        row2.add(1);
        expectedResult.add(row2);
        List<Integer> row3 = new ArrayList<>();
        row3.add(1);
        row3.add(2);
        row3.add(1);
        expectedResult.add(row3);
        List<Integer> row4 = new ArrayList<>();
        row4.add(1);
        row4.add(3);
        row4.add(3);
        row4.add(1);
        expectedResult.add(row4);
        List<Integer> row5 = new ArrayList<>();
        row5.add(1);
        row5.add(4);
        row5.add(6);
        row5.add(4);
        row5.add(1);
        expectedResult.add(row5);

        PascalsTriangle solution = new PascalsTriangle();
        Assertions.assertEquals(expectedResult.toString(), solution.generate(5).toString());
    }

    @Test
    void testCase2() {
        List<List<Integer>> expectedResult = new ArrayList<>();
        List<Integer> row1 = new ArrayList<>();
        row1.add(1);
        expectedResult.add(row1);

        PascalsTriangle solution = new PascalsTriangle();
        Assertions.assertEquals(expectedResult.toString(), solution.generate(1).toString());
    }

}
