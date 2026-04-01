package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 13. Roman to Integer
 * <p>
 * Roman numerals are represented by seven different symbols: I, V, X, L, C, D and M.
 * <p>
 * Symbol       Value
 * I             1
 * V             5
 * X             10
 * L             50
 * C             100
 * D             500
 * M             1000
 * For example, 2 is written as II in Roman numeral, just two ones added together. 12 is written as XII, which is simply X + II. The number 27 is written as XXVII, which is XX + V + II.
 * <p>
 * Roman numerals are usually written largest to smallest from left to right. However, the numeral for four is not IIII. Instead, the number four is written as IV. Because the one is before the five we subtract it making four. The same principle applies to the number nine, which is written as IX. There are six instances where subtraction is used:
 * <p>
 * I can be placed before V (5) and X (10) to make 4 and 9.
 * X can be placed before L (50) and C (100) to make 40 and 90.
 * C can be placed before D (500) and M (1000) to make 400 and 900.
 * Given a roman numeral, convert it to an integer.
 */
public class RomanToInteger {

    private final static String[] THOUSANDS = {"", "M", "MM", "MMM", "MMMM"};
    private final static String[] HUNDREDS = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
    private final static String[] TENS = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
    private final static String[] UNITS = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};

    public int romanToInt(String romanNumber) {
        if (romanNumber == null) {
            throw new IllegalArgumentException("Roman number cannot be null");
        }
        if (romanNumber.isEmpty()) {
            return 0;
        }

        int number = 0;

        for (int i = THOUSANDS.length - 1; i >= 0; i--) {
            if (romanNumber.startsWith(THOUSANDS[i])) {
                number += i * 1000;
                romanNumber = romanNumber.substring(THOUSANDS[i].length());
                break;
            }
        }

        for (int i = HUNDREDS.length - 1; i >= 0; i--) {
            if (romanNumber.startsWith(HUNDREDS[i])) {
                number += i * 100;
                romanNumber = romanNumber.substring(HUNDREDS[i].length());
                break;
            }
        }

        for (int i = TENS.length - 1; i >= 0; i--) {
            if (romanNumber.startsWith(TENS[i])) {
                number += i * 10;
                romanNumber = romanNumber.substring(TENS[i].length());
                break;
            }
        }

        for (int i = UNITS.length - 1; i >= 0; i--) {
            if (romanNumber.startsWith(UNITS[i])) {
                number += i;
                romanNumber = romanNumber.substring(UNITS[i].length());
                break;
            }
        }
        if (!romanNumber.isEmpty()) {
            throw new IllegalArgumentException("Roman number is invalid");
        }

        return number;
    }
}
