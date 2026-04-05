package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/*
 * 205. Isomorphic Strings
 *
 * LeetCode: https://leetcode.com/problems/isomorphic-strings/
 *
 * Given two strings s and t, determine if they are isomorphic.
 *
 * Two strings s and t are isomorphic if the characters in s can be replaced to get t.
 *
 * All occurrences of a character must be replaced with another character while preserving the order of characters.
 * No two characters may map to the same character, but a character may map to itself.
 */
public class IsomorphicStrings {

    public boolean isIsomorphic(String s, String t) {
        Map<Character, Character> map = new HashMap<>();
        Set<Character> busy = new HashSet<>();

        for (int i = 0; i < s.length(); i++) {
            char ch1 = s.charAt(i);
            char ch2 = t.charAt(i);

            if (!map.containsKey(ch1) && !busy.contains(ch2)) {
                map.put(ch1, ch2);
                busy.add(ch2);
            } else if (!map.containsKey(ch1) && busy.contains(ch2)) {
                return false;
            } else if (ch2 != map.get(ch1)) {
                return false;
            }
        }
        return true;
    }

}
