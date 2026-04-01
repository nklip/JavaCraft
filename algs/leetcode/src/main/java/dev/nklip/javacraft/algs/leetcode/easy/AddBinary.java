package dev.nklip.javacraft.algs.leetcode.easy;

import java.math.BigInteger;

/**
 * 67. Add Binary
 * <p>
 * Given two binary strings a and b, return their sum as a binary string.
 */
public class AddBinary {

    /**
     * It adds two binary strings the same way you'd do long addition by hand, right to left:
     *    1 0 1 0
     *  + 1 0 1 1
     *  ---------
     *  1 0 1 0 1
     */
    public String addBinary(String a, String b) {
        StringBuilder result = new StringBuilder();
        int i = a.length() - 1;
        int j = b.length() - 1;
        int carry = 0;

        while (i >= 0 || j >= 0 || carry > 0) {
            int sum = carry;
            if (i >= 0)  {
                // 1. Explanation of the black magic
                //
                // a is a String
                // charAt(i--) returns a char
                // If the string contains "0" or "1", then it returns the character '0' or '1'
                //
                // 2. In Java, char values are stored as Unicode integers.
                // <p>
                // For example:
                // '0' → 48
                // '1' → 49
                // '2' → 50
                //
                // So when you write:
                //
                // a.charAt(i) - '0'
                //
                // You’re actually doing:
                // (ASCII value of digit) - 48
                //
                // Example:
                //
                // '0' - '0' = 48 - 48 = 0
                // '1' - '0' = 49 - 48 = 1
                // '2' - '0' = 50 - 48 = 2
                // So this converts a digit character into its numeric value.
                sum += a.charAt(i--) - '0'; // converts the char '0'/'1' to int 0/1
            }
            if (j >= 0) {
                sum += b.charAt(j--) - '0'; // converts the char '0'/'1' to int 0/1
            }

            result.append(sum % 2); // the current bit (0 or 1), appended to result
            carry = sum / 2; // the new carry (0 or 1)
        }

        return result.reverse().toString();
    }

    // use BigInteger
    public String addBinary2(String aStr, String bStr) {
        // Use as radix 2 because it's binary
        BigInteger a = new BigInteger(aStr, 2);
        BigInteger b = new BigInteger(bStr, 2);

        BigInteger sum = a.add(b);
        return sum.toString(2);
    }

}
