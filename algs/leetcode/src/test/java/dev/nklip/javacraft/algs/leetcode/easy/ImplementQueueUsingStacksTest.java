package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ImplementQueueUsingStacksTest {
    @Test
    public void testCase1() {
        ImplementQueueUsingStacks solution = new ImplementQueueUsingStacks();

        Assertions.assertTrue(solution.empty());
        solution.push(1);
        Assertions.assertFalse(solution.empty());
        Assertions.assertEquals(1, solution.peek());
        solution.push(2);
        Assertions.assertEquals(1, solution.peek());
        Assertions.assertEquals(1, solution.pop());
        Assertions.assertEquals(2, solution.peek());
        Assertions.assertFalse(solution.empty());
    }
}
