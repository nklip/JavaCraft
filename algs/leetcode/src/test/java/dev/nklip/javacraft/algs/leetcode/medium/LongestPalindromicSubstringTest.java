package dev.nklip.javacraft.algs.leetcode.medium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LongestPalindromicSubstringTest {
    @Test
    void testCase1() {
        LongestPalindromicSubstring solution = new LongestPalindromicSubstring();

        // Explanation: "aba" is also a valid answer.
        Assertions.assertEquals("bab", solution.longestPalindrome("babad"));
        Assertions.assertEquals("bab", solution.longestPalindrome2("babad"));
    }

    @Test
    void testCase2() {
        LongestPalindromicSubstring solution = new LongestPalindromicSubstring();

        // Explanation: "aba" is also a valid answer.
        Assertions.assertEquals("bb", solution.longestPalindrome("cbbd"));
        Assertions.assertEquals("bb", solution.longestPalindrome2("cbbd"));
    }

    @Test
    void testCase3() {
        LongestPalindromicSubstring solution = new LongestPalindromicSubstring();

        // Explanation: "a" is also a valid answer.
        Assertions.assertEquals("a", solution.longestPalindrome("a"));
        Assertions.assertEquals("a", solution.longestPalindrome2("a"));
    }

    @Test
    void testCase4() {
        LongestPalindromicSubstring solution = new LongestPalindromicSubstring();

        // Explanation: "a" is also a valid answer.
        Assertions.assertEquals("a", solution.longestPalindrome("ac"));
        Assertions.assertEquals("a", solution.longestPalindrome2("ac"));
    }

    @Test
    void testCase5() {
        LongestPalindromicSubstring solution = new LongestPalindromicSubstring();

        // Explanation: "bb" is also a valid answer.
        Assertions.assertEquals("bb", solution.longestPalindrome("bb"));
        Assertions.assertEquals("bb", solution.longestPalindrome2("bb"));
    }

    @Test
    void testCase6() {
        LongestPalindromicSubstring solution = new LongestPalindromicSubstring();

        // Explanation: "ccc" is also a valid answer.
        Assertions.assertEquals("ccc", solution.longestPalindrome("ccc"));
        Assertions.assertEquals("ccc", solution.longestPalindrome2("ccc"));
    }

    @Test
    void testCase7() {
        LongestPalindromicSubstring solution = new LongestPalindromicSubstring();

        // Explanation: "aaaa" is also a valid answer.
        Assertions.assertEquals("aaaa", solution.longestPalindrome("aaaa"));
        Assertions.assertEquals("aaaa", solution.longestPalindrome2("aaaa"));
    }

    @Test
    void testCase8() {
        LongestPalindromicSubstring solution = new LongestPalindromicSubstring();

        // Explanation: "tattarrattat" is also a valid answer.
        Assertions.assertEquals("tattarrattat", solution.longestPalindrome("tattarrattat"));
        Assertions.assertEquals("tattarrattat", solution.longestPalindrome2("tattarrattat"));
    }
}
