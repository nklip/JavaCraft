package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 171. Excel Sheet Column Number
 * <p>
 * Given a string columnTitle that represents the column title as appears in an Excel sheet,
 * return its corresponding column number.
 * <p>
 * For example:
 * <p>
 * A -> 1
 * B -> 2
 * C -> 3
 * ...
 * Z -> 26
 * AA -> 27
 * AB -> 28
 * ...
 */
public class ExcelSheetColumnNumber {

    /**
     * For every additional digit of the string, we multiply the value of the digit by 26^n
     * where n is the number of digits it is away from the one's place.
     * <p>
     * This is similar to how the number 254 could be broken down as this:
     * (2 x 10 x 10) + (5 x 10) + (4).
     * The reason we use 26 instead of 10 is because 26 is our base.
     */
    public int titleToNumber(String columnTitle) {
        int result = 0;
        for (char ch : columnTitle.toCharArray()) {
            result *= 26; // is the amount of chars in alphabet

            // ASCII / Unicode values
            // for example: 'C' - 'A' = 2
            int number = ch - 'A' + 1;

            result += number;
        }

        return result;
    }
}
