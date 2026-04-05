package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class IsomorphicStringsTest {
    @Test
    void testCase1() {
        IsomorphicStrings solution = new IsomorphicStrings();
        // The strings s and t can be made identical by:
        Assertions.assertTrue(solution.isIsomorphic("egg", "add"));
    }

    @Test
    void testCase2() {
        IsomorphicStrings solution = new IsomorphicStrings();
        // The strings s and t can not be made identical as '1' needs to be mapped to both '2' and '3'.
        Assertions.assertFalse(solution.isIsomorphic("f11", "b23"));
    }

    @Test
    void testCase3() {
        IsomorphicStrings solution = new IsomorphicStrings();
        Assertions.assertTrue(solution.isIsomorphic("paper", "title"));
    }

    @Test
    void testCase4() {
        IsomorphicStrings solution = new IsomorphicStrings();
        Assertions.assertFalse(solution.isIsomorphic("badc", "baba"));
    }
}
