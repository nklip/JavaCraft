package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 9. Palindrome Number
 * <p>
 * Given an integer x, return true if x is a palindrome, and false otherwise.
 */
public class PalindromeNumber {

    // half-reversal
    public boolean isPalindrome(int x) {
        // all negative numbers and multiples of 10 (except 0) are not palindromes
        if (x < 0 || (x % 10 == 0 && x != 0)) {
            return false;
        }
        int reversed = 0;
        while (x > reversed) {
            reversed = reversed * 10 + (x % 10);
            x /= 10; // delete last digit
        }
        // even-length: x == reversed, odd-length: x == reversed / 10
        return x == reversed || x == reversed / 10;
    }

    // simplest - we use a text array to solve it
    public boolean isPalindrome2(int x) {
        // all negative numbers are not palindrome
        if (x < 0) {
            return false;
        }
        char []numberChar = Integer.toString(x).toCharArray();

        int charLength = numberChar.length;
        for (int i = 0; i < (charLength / 2); i++) {
            if (numberChar[i] != numberChar[charLength - 1 - i]) {
                return false;
            }
        }

        return true;
    }

}
