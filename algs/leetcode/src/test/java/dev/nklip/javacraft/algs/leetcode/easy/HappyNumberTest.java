package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HappyNumberTest {

    @Test
    void testCase1() {
        HappyNumber solution = new HappyNumber();

        // Input: n = 19
        // Output: true
        // Explanation:
        // 12 + 92 = 82
        // 82 + 22 = 68
        // 62 + 82 = 100
        // 12 + 02 + 02 = 1
        Assertions.assertTrue(solution.isHappy(19));
    }

    @Test
    void testCase2() {
        HappyNumber solution = new HappyNumber();

        Assertions.assertFalse(solution.isHappy(2));
    }
}
