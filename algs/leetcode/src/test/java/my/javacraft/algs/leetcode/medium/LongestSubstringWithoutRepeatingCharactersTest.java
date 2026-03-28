package my.javacraft.algs.leetcode.medium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LongestSubstringWithoutRepeatingCharactersTest {

    @Test
    void testCase1() {
        LongestSubstringWithoutRepeatingCharacters solution
                = new LongestSubstringWithoutRepeatingCharacters();

        // Explanation: The answer is "abc", with the length of 3.
        // Note that "bca" and "cab" are also correct answers.
        Assertions.assertEquals(3, solution.lengthOfLongestSubstring("abcabcbb"));
        Assertions.assertEquals(3, solution.lengthOfLongestSubstring2("abcabcbb"));
    }

    @Test
    void testCase2() {
        LongestSubstringWithoutRepeatingCharacters solution
                = new LongestSubstringWithoutRepeatingCharacters();

        // Explanation: The answer is "b", with the length of 1.
        Assertions.assertEquals(1, solution.lengthOfLongestSubstring("bbbbb"));
        Assertions.assertEquals(1, solution.lengthOfLongestSubstring2("bbbbb"));
    }

    @Test
    void testCase3() {
        LongestSubstringWithoutRepeatingCharacters solution
                = new LongestSubstringWithoutRepeatingCharacters();

        // Explanation: The answer is "wke", with the length of 3.
        // Notice that the answer must be a substring, "pwke" is a subsequence and not a substring.
        Assertions.assertEquals(3, solution.lengthOfLongestSubstring("pwwkew"));
        Assertions.assertEquals(3, solution.lengthOfLongestSubstring2("pwwkew"));
    }

    @Test
    void testCase4() {
        LongestSubstringWithoutRepeatingCharacters solution
                = new LongestSubstringWithoutRepeatingCharacters();

        Assertions.assertEquals(3, solution.lengthOfLongestSubstring("dvdf"));
        Assertions.assertEquals(3, solution.lengthOfLongestSubstring2("dvdf"));
    }

    @Test
    void testCase5() {
        LongestSubstringWithoutRepeatingCharacters solution
                = new LongestSubstringWithoutRepeatingCharacters();

        Assertions.assertEquals(2, solution.lengthOfLongestSubstring("cdd"));
        Assertions.assertEquals(2, solution.lengthOfLongestSubstring2("cdd"));
    }
}
