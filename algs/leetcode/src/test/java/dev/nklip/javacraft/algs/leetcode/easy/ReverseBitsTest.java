package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ReverseBitsTest {
    @Test
    void testCase1() {
        ReverseBits solution = new ReverseBits();
        Assertions.assertEquals(964176192, solution.reverseBits(43261596));
    }

    @Test
    void testCase2() {
        ReverseBits solution = new ReverseBits();
        Assertions.assertEquals(1073741822, solution.reverseBits(2147483644));
    }

    @Test
    void testCase3() {
        ReverseBits solution = new ReverseBits();
        Assertions.assertEquals(0, solution.reverseBits(0));
    }

    @Test
    void testCase4() {
        ReverseBits solution = new ReverseBits();
        Assertions.assertEquals(Integer.MIN_VALUE, solution.reverseBits(1));
    }

    @Test
    void testCase5() {
        ReverseBits solution = new ReverseBits();
        Assertions.assertEquals(-2, solution.reverseBits(Integer.MAX_VALUE));
    }
}
