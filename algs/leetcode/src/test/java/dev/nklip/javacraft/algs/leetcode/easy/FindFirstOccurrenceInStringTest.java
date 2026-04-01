package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class FindFirstOccurrenceInStringTest {

    @Test
    void testCase1() {
        FindFirstOccurrenceInString solution = new FindFirstOccurrenceInString();
        Assertions.assertEquals(0, solution.strStr("sadbutsad", "sad"));
        Assertions.assertEquals(0, solution.strStr2("sadbutsad", "sad"));
    }

    @Test
    void testCase2() {
        FindFirstOccurrenceInString solution = new FindFirstOccurrenceInString();
        Assertions.assertEquals(-1, solution.strStr("leetcode", "leeto"));
        Assertions.assertEquals(-1, solution.strStr2("leetcode", "leeto"));
    }

    @Test
    void testCase3() {
        FindFirstOccurrenceInString solution = new FindFirstOccurrenceInString();
        Assertions.assertEquals(0, solution.strStr("a", "a"));
        Assertions.assertEquals(0, solution.strStr2("a", "a"));
    }

    @Test
    void testCase4() {
        FindFirstOccurrenceInString solution = new FindFirstOccurrenceInString();
        Assertions.assertEquals(-1, solution.strStr("aaa", "aaaa"));
        Assertions.assertEquals(-1, solution.strStr2("aaa", "aaaa"));
    }

    @Test
    void testCase5() {
        FindFirstOccurrenceInString solution = new FindFirstOccurrenceInString();
        Assertions.assertEquals(-1, solution.strStr("mississippi", "issipi"));
        Assertions.assertEquals(-1, solution.strStr2("mississippi", "issipi"));
    }

    @Test
    void testCase6() {
        FindFirstOccurrenceInString solution = new FindFirstOccurrenceInString();
        Assertions.assertEquals(4, solution.strStr("mississippi", "issip"));
        Assertions.assertEquals(4, solution.strStr2("mississippi", "issip"));
    }

}
