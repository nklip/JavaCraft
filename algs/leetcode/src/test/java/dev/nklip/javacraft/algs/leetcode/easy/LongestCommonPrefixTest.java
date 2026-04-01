package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LongestCommonPrefixTest {

    @Test
    void testSimpleCase1() {
        LongestCommonPrefix solution = new LongestCommonPrefix();

        String []array = {"flower", "flow", "flight"};
        String prefix = solution.longestCommonPrefix(array);

        Assertions.assertEquals("fl", prefix);
    }

    @Test
    void testSimpleCase2() {
        LongestCommonPrefix solution = new LongestCommonPrefix();

        String []array = {"a", "b"};
        String prefix = solution.longestCommonPrefix(array);

        Assertions.assertEquals("", prefix);
    }

    @Test
    void testSimpleCase3() {
        LongestCommonPrefix solution = new LongestCommonPrefix();

        String []array = {"a", "ac"};
        String prefix = solution.longestCommonPrefix(array);

        Assertions.assertEquals("a", prefix);
    }

    @Test
    void testSimpleNegativeCase() {
        LongestCommonPrefix solution = new LongestCommonPrefix();

        String []array = {"dog", "car", "moon"};
        String prefix = solution.longestCommonPrefix(array);

        Assertions.assertEquals("", prefix);
    }
}
