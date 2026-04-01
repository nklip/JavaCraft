package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 66. Plus One
 * <p>
 * You are given a large integer represented as an integer array digits, where each digits[i] is the ith digit of the integer.
 * The digits are ordered from most significant to least significant in left-to-right order.
 * The large integer does not contain any leading 0's.
 * <p>
 * Increment the large integer by one and return the resulting array of digits.
 */
public class PlusOne {

    public int[] plusOne(int[] digits) {
        boolean newArray = false;
        for (int i = digits.length - 1; i >= 0; i--) {
            int digit = digits[i];
            if (digit == 9) {
                digits[i] = 0;
                if (i == 0) {
                    newArray = true;
                }
            } else {
                digits[i] = digit + 1;
                break;
            }
        }
        if (newArray) {
            int [] returnArray = new int[digits.length + 1];
            returnArray[0] = 1;
            System.arraycopy(digits, 0, returnArray, 1, digits.length - 1);
            return returnArray;
        }
        return digits;
    }

}
