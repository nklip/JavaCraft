package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PalindromeNumberTest {

    @Test
    void testCase1() {
        PalindromeNumber solution = new PalindromeNumber();
        Assertions.assertTrue(solution.isPalindrome(121));
    }

    @Test
    void testCase2() {
        PalindromeNumber solution = new PalindromeNumber();
        Assertions.assertFalse(solution.isPalindrome(-121));
    }

    @Test
    void testCase3() {
        PalindromeNumber solution = new PalindromeNumber();
        Assertions.assertFalse(solution.isPalindrome(10));
    }

    @Test
    void testCase4() {
        PalindromeNumber solution = new PalindromeNumber();
        Assertions.assertTrue(solution.isPalindrome(2222));
    }

    @Test
    void testCase5() {
        PalindromeNumber solution = new PalindromeNumber();
        Assertions.assertTrue(solution.isPalindrome2(2222));
    }
}
