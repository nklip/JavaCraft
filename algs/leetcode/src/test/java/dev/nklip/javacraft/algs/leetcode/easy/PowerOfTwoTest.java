package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PowerOfTwoTest {
    @Test
    public void isPowerOfTwo() {
        PowerOfTwo p = new PowerOfTwo();
        Assertions.assertTrue(p.isPowerOfTwo(1));
        Assertions.assertTrue(p.isPowerOfTwo(2));
        Assertions.assertTrue(p.isPowerOfTwo(4));
        Assertions.assertTrue(p.isPowerOfTwo(16));

        Assertions.assertFalse(p.isPowerOfTwo(3));
        Assertions.assertFalse(p.isPowerOfTwo(5));
        Assertions.assertFalse(p.isPowerOfTwo(6));
    }
}
