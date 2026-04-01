package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ValidParenthesesTest {

    @Test
    void testCase1() {
        ValidParentheses solution = new ValidParentheses();

        Assertions.assertTrue(solution.isValid("()"));
        Assertions.assertTrue(solution.isValid("()[]{}"));
        Assertions.assertTrue(solution.isValid("([])"));
    }

    @Test
    void testCase2() {
        ValidParentheses solution = new ValidParentheses();

        Assertions.assertFalse(solution.isValid("(]"));
        Assertions.assertFalse(solution.isValid("([)]"));
    }

}
