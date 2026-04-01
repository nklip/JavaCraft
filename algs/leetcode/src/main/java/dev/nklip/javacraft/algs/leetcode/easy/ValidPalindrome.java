package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 125. Valid Palindrome
 * <p>
 * A phrase is a palindrome if, after converting all uppercase letters into lowercase letters
 * and removing all non-alphanumeric characters, it reads the same forward and backward.
 * Alphanumeric characters include letters and numbers.
 * <p>
 * Given a string s, return true if it is a palindrome, or false otherwise.
 */
public class ValidPalindrome {

    public boolean isPalindrome(String inputString) {
        inputString = excludeNonAlphanumericCharacters(inputString);
        return isAlphanumericPalindrome(inputString);
    }

    private String excludeNonAlphanumericCharacters(String inputString) {
        StringBuilder out = new StringBuilder(inputString.length());
        for (int i = 0; i < inputString.length(); i++) {
            char ch = inputString.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }

    private boolean isAlphanumericPalindrome(String s) {
        // classical two-pointer loop
        for (int left = 0, right = s.length() - 1; left < right; left++, right--) {
            if (s.charAt(left) != s.charAt(right)) {
                return false;
            }
        }
        return true;
    }

}
