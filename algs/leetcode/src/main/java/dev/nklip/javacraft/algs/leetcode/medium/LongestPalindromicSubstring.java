package dev.nklip.javacraft.algs.leetcode.medium;

/*
 * 5. Longest Palindromic Substring
 *
 * LeetCode: https://leetcode.com/problems/longest-palindromic-substring/
 *
 * Given a string s, return the longest palindromic substring in s.
 */
public class LongestPalindromicSubstring {

    // search for a palindrome based on mirroring from center of a palindrome
    // Every palindrome has a CENTER.
    //
    // Odd palindrome → center is one character
    // -> b a b
    //
    // Even palindrome → center is between two characters
    // -> b | b
    public String longestPalindrome(final String input) {
        if (input.length() == 1) {
            return input;
        }
        String result = "";
        for (int i = 0; i < input.length(); i++) {
            // odd length palindrome
            int left = i;
            int right = i;
            while (left >= 0 && right < input.length() && input.charAt(left) == input.charAt(right)) {
                left--;
                right++;
            }
            String substring = input.substring(left + 1, right);
            if (result.length() < substring.length()) {
                result = substring;
            }

            // even length palindrome
            left = i;
            right = i + 1;
            while (left >= 0 && right < input.length() && input.charAt(left) == input.charAt(right)) {
                left--;
                right++;
            }
            substring = input.substring(left + 1, right);
            if (result.length() < substring.length()) {
                result = substring;
            }
        }
        return result;
    }

    // brute force
    public String longestPalindrome2(String inputString) {
        if (inputString.length() == 1) {
            return inputString;
        }
        String result = "";
        for (int left = 0; left < inputString.length(); left++) {
            for (int right = left + 1; right <= inputString.length(); right++) {
                String substring = inputString.substring(left, right);

                if (isPalindrome(substring)) {
                    if (result.length() < substring.length()) {
                        result = substring;
                    }
                }
            }
        }
        return result;
    }

    private boolean isPalindrome(String str) {
        for (int left = 0; left < str.length(); left++) {
            int right = str.length() - left - 1;
            if (str.charAt(left) != str.charAt(right)) {
                return false;
            }
            if (left > right) {
                break;
            }
        }
        return true;
    }

}
