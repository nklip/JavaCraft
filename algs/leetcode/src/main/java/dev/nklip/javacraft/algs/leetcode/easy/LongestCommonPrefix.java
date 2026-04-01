package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 14. Longest Common Prefix
 * <p>
 * Write a function to find the longest common prefix string amongst an array of strings.
 * <p>
 * If there is no common prefix, return an empty string "".
 */
public class LongestCommonPrefix {

    public String longestCommonPrefix(String[] strs) {
        if (strs.length == 0) {
            return "";
        }

        String tempPrefix = strs[0];
        char []tempPrefixChars = tempPrefix.toCharArray();
        int end = tempPrefix.length();

        for (int i = 1; i < strs.length && end >= 1; i++) {
            char []tempChars = strs[i].toCharArray();

            if (end > tempChars.length) {
                end = tempChars.length;
            }

            for (int j = 0; j < tempChars.length && j < end; j++) {
                char tempCh = tempChars[j];

                if (tempPrefixChars[j] != tempCh) {
                    end = j;
                }

            }

        }

        if (end > 0) {
            return tempPrefix.substring(0, end);
        } else {
            return "";
        }
    }

}
