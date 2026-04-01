package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LengthOfLastWordTest {

    @Test
    void testCase1() {
        LengthOfLastWord solution = new LengthOfLastWord();
        Assertions.assertEquals(5, solution.lengthOfLastWord("Hello World"));
    }

    @Test
    void testCase2() {
        LengthOfLastWord solution = new LengthOfLastWord();
        Assertions.assertEquals(4, solution.lengthOfLastWord("   fly me   to   the moon  "));
    }

    @Test
    void testCase3() {
        LengthOfLastWord solution = new LengthOfLastWord();
        Assertions.assertEquals(6, solution.lengthOfLastWord("luffy is still joyboy"));
    }

    @Test
    void testCase4() {
        LengthOfLastWord solution = new LengthOfLastWord();
        Assertions.assertEquals(1, solution.lengthOfLastWord("a"));
    }

    @Test
    void testCase5() {
        LengthOfLastWord solution = new LengthOfLastWord();
        Assertions.assertEquals(1, solution.lengthOfLastWord("a "));
    }

    @Test
    void testCase6() {
        LengthOfLastWord solution = new LengthOfLastWord();
        Assertions.assertEquals(3, solution.lengthOfLastWord("daa     "));
    }

}
