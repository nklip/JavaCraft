package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.ArrayList;
import java.util.List;

/**
 * 118. Pascal's Triangle
 * <p>
 * Given an integer numRows, return the first numRows of Pascal's triangle.
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
public class PascalsTriangle {

    public List<List<Integer>> generate(int numRows) {
        List<List<Integer>> returnList = new ArrayList<>();
        List<Integer> row1 = new ArrayList<>();
        row1.add(1);
        returnList.add(row1);
        if (numRows == 1) {
            return returnList;
        }
        List<Integer> row2 = new ArrayList<>();
        row2.add(1);
        row2.add(1);
        returnList.add(row2);
        if (numRows == 2) {
            return returnList;
        }
        pascalTriangle(numRows, 3, returnList);
        return returnList;
    }

    private void pascalTriangle(
            final int numRows, final int rows, List<List<Integer>> returnList) {
        List<Integer> previousRow = returnList.get(rows - 2);
        List<Integer> row = new ArrayList<>(rows);
        row.add(1);
        for (int i = 1; i < rows - 1; i++) {
            int sum = previousRow.get(i - 1) + previousRow.get(i);
            row.add(sum);
        }
        row.add(1);
        returnList.add(row);

        if (numRows > rows) {
            pascalTriangle(numRows, rows + 1, returnList);
        }
    }
}
