package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ValidPalindromeTest {

    @Test
    void testCase1() {
        ValidPalindrome solution = new ValidPalindrome();

        // Explanation: "amanaplanacanalpanama" is a palindrome.
        Assertions.assertTrue(solution.isPalindrome("A man, a plan, a canal: Panama"));
    }

    @Test
    void testCase2() {
        ValidPalindrome solution = new ValidPalindrome();

        // Explanation: "raceacar" is not a palindrome.
        Assertions.assertFalse(solution.isPalindrome("race a car"));
    }

    @Test
    void testCase3() {
        ValidPalindrome solution = new ValidPalindrome();

        // Explanation: s is an empty string "" after removing non-alphanumeric characters.
        // Since an empty string reads the same forward and backward, it is a palindrome.
        Assertions.assertTrue(solution.isPalindrome(" "));
    }
}
