package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 168. Excel Sheet Column Title
 * <p>
 * Given an integer columnNumber, return its corresponding column title as it appears in an Excel sheet.
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
public class ExcelSheetColumnTitle {

    // columnNumber - 1 - 26
    // columnNumber - 2 - 702
    // columnNumber - 3 - 18278
    // columnNumber - 4 - 475254
    // columnNumber - 5 - 12356630
    // columnNumber - 6 - 321272406
    // columnNumber - 7 - Integer.MAX_VALUE

    public String convertToTitle(final int columnNumber) {
        int columnRowId = columnNumber;
        StringBuilder title = new StringBuilder();
        while (columnRowId > 0) {
            columnRowId--;
            char ch = (char)('A' + columnRowId % 26);
            title.append(ch);
            columnRowId = columnRowId / 26;
        }
        return title.reverse().toString();
    }

}
