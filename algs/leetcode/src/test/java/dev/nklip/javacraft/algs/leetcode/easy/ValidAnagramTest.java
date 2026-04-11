package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ValidAnagramTest {
    @Test
    void testCase1() {
        ValidAnagram solution = new ValidAnagram();

        Assertions.assertTrue(solution.isAnagramPure("anagram", "nagaram"));
        Assertions.assertTrue(solution.isAnagram("anagram", "nagaram"));
    }

    @Test
    void testCase2() {
        ValidAnagram solution = new ValidAnagram();

        Assertions.assertFalse(solution.isAnagramPure("rat", "car"));
        Assertions.assertFalse(solution.isAnagram("rat", "car"));
    }

    @Test
    void testCase3() {
        ValidAnagram solution = new ValidAnagram();

        Assertions.assertFalse(solution.isAnagramPure("a", "ab"));
        Assertions.assertFalse(solution.isAnagram("a", "ab"));
    }

    @Test
    void testCase4() {
        ValidAnagram solution = new ValidAnagram();

        Assertions.assertTrue(solution.isAnagram("-=", "=-"));
    }

    @Test
    void testCase5() {
        ValidAnagram solution = new ValidAnagram();

        Assertions.assertTrue(solution.isAnagram("ABAB", "aabb"));
    }
}
