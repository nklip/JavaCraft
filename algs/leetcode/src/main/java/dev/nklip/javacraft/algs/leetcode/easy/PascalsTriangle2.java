package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.List;

/**
 * 119. Pascal's Triangle II
 * <p>
 * Given an integer rowIndex, return the rowIndexth (0-indexed) row of the Pascal's triangle.
 * <p>
 * In Pascal's triangle, each number is the sum of the two numbers directly above it as shown:
 * First six rows:
 *         1
 *        1 1
 *       1 2 1
 *      1 3 3 1
 *     1 4 6 4 1
 *    1 5 10 10 5 1
 */
public class PascalsTriangle2 {

    public List<Integer> getRow(int rowIndex) {
        List<Integer> row1 = new ArrayList<>();
        row1.add(1);
        if (rowIndex == 0) {
            return row1;
        }
        List<Integer> row2 = new ArrayList<>();
        row2.add(1);
        row2.add(1);
        if (rowIndex == 1) {
            return row2;
        }
        return pascalTriangle(rowIndex, 2, row2);
    }

    private List<Integer> pascalTriangle(
            final int numRows, final int rows, List<Integer> previousRow) {
        List<Integer> row = new ArrayList<>(rows);
        row.add(1);
        for (int i = 0; i < rows - 1; i++) {
            int sum = previousRow.get(i) + previousRow.get(i + 1);
            row.add(sum);
        }
        row.add(1);

        if (numRows > rows) {
            return pascalTriangle(numRows, rows + 1, row);
        } else {
            return row;
        }
    }

}
