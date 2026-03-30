package my.javacraft.algs.leetcode.medium;

/*
 * 6. Zigzag Conversion
 *
 * LeetCode: https://leetcode.com/problems/zigzag-conversion/
 *
 * The string "PAYPALISHIRING" is written in a zigzag pattern on a given number of rows like this:
 * (you may want to display this pattern in a fixed font for better legibility)
 *
 * P   A   H   N
 * A P L S I I G
 * Y   I   R
 * And then read line by line: "PAHNAPLSIIGYIR"
 *
 * Write the code that will take a string and make this conversion given a number of rows:
 *
 * string convert(string s, int numRows);
 */
public class ZigZagConversion {

    public String convert(final String input, final int numRows) {
        // optimization
        if (numRows == 1 || numRows >= input.length()) {
            return input;
        }
        int numCols = input.length();
        String[][] dp = new String[numRows][numCols];

        int row = -1;
        int col = 0;
        boolean isDown = true;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            String value = Character.toString(ch);

            if (isDown) {
                dp[++row][col] = value;

                if (row == numRows - 1) {
                    isDown = false;
                }
            } else {
                dp[--row][++col] = value;

                if (row == 0) {
                    isDown = true;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                String temp = dp[r][c];
                if (temp != null) {
                    sb.append(temp);
                }
            }
        }
        return sb.toString();
    }

}
