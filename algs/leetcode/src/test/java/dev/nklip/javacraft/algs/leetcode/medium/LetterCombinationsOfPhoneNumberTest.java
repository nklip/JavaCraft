package dev.nklip.javacraft.algs.leetcode.medium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LetterCombinationsOfPhoneNumberTest {
    @Test
    void testCase1() {
        LetterCombinationsOfPhoneNumber solution = new LetterCombinationsOfPhoneNumber();

        Assertions.assertEquals(
                "[ad, bd, cd, ae, be, ce, af, bf, cf]",
                solution.letterCombinations("23").toString()
        );
    }

    @Test
    void testCase2() {
        LetterCombinationsOfPhoneNumber solution = new LetterCombinationsOfPhoneNumber();

        Assertions.assertEquals(
                "[a, b, c]",
                solution.letterCombinations("2").toString()
        );
    }
}
