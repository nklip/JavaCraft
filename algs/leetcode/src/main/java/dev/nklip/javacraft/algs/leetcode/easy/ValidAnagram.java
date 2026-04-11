package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/*
 * 242. Valid Anagram
 *
 * LeetCode: https://leetcode.com/problems/valid-anagram/
 *
 * Given two strings s and t, return true if t is an anagram of s, and false otherwise.
 */
public class ValidAnagram {

    // should work only for alphabetic characters in lowercase only
    public boolean isAnagramPure(String input1, String input2) {
        if (input1.length() != input2.length()) {
            return false;
        }

        char[] array = new char[26];

        for (int i = 0; i < input1.length(); i++) {
            array[input1.charAt(i) - 'a']++;
            array[input2.charAt(i) - 'a']--;
        }

        for (char c : array) {
            if (c != 0) {
                return false;
            }
        }
        return true;
    }

    // in most anagram problems, comparison is case-insensitive
    public boolean isAnagram(String input1, String input2) {
        if (input1.length() != input2.length()) {
            return false;
        }

        // maps are better if your anagram should work with Unicode characters
        // as the amount of assigned Unicode characters ~ 297k as of now (April 2026)
        Map<Character, Integer> inputMap1 = new HashMap<>();
        Map<Character, Integer> inputMap2 = new HashMap<>();

        for (int i = 0; i < input1.length(); i++) {
            inputMap1.merge(
                    Character.toLowerCase(input1.charAt(i)),
                    1,
                    Integer::sum
            );

            inputMap2.merge(
                    Character.toLowerCase(input2.charAt(i)),
                    1,
                    Integer::sum
            );
        }

        for (Map.Entry<Character, Integer> entry : inputMap1.entrySet()) {
            if (!Objects.equals(entry.getValue(), inputMap2.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

}
