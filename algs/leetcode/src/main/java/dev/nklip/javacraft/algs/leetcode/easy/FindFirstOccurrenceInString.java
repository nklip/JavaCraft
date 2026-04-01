package dev.nklip.javacraft.algs.leetcode.easy;

/*
 * 28. Find the Index of the First Occurrence in a String
 *
 * LeetCode: https://leetcode.com/problems/find-the-index-of-the-first-occurrence-in-a-string/
 *
 * Given two strings needle and haystack, return the index of the first occurrence of needle in haystack,
 * or -1 if needle is not part of haystack.
 */
public class FindFirstOccurrenceInString {

    public int strStr(String haystackStr, String needleStr) {
        char []haystack = haystackStr.toCharArray();
        char []needle = needleStr.toCharArray();
        if (haystack.length < needle.length) {
            return -1;
        }
        int index = -1;

        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (haystack[i] == needle[0]) {
                for (int y = 1; y < needle.length; y++) {
                    if (haystack.length < i + y + 1) {
                        break;
                    }
                    if (haystack[i + y] != needle[y]) {
                        break;
                    }
                    if (haystack[i + y] == needle[y] && y + 1 == needle.length) {
                        index = i;
                        break;
                    }
                }
                if (index != -1) {
                    break;
                } else if (needle.length == 1) {
                    index = i;
                    break;
                }
            }
        }
        return index;
    }

    // the easiest solution in java
    public int strStr2(String haystack, String needle) {
        return haystack.indexOf(needle);
    }

}
