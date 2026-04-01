package dev.nklip.javacraft.algs.leetcode.medium;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
 * 17. Letter Combinations of a Phone Number
 *
 * LeetCode: https://leetcode.com/problems/letter-combinations-of-a-phone-number/
 *
 * Given a string containing digits from 2-9 inclusive,
 * return all possible letter combinations that the number could represent.
 * Return the answer in any order.
 *
 * A mapping of digits to letters (just like on the telephone buttons) is given below.
 * Note that 1 does not map to any letters.
 */
public class LetterCombinationsOfPhoneNumber {

    private final Map<String, String> intMap = Map.of(
            "2", "abc",
            "3", "def",
            "4", "ghi",
            "5", "jkl",
            "6", "mno",
            "7", "pqrs",
            "8", "tuv",
            "9", "wxyz"
    );

    public List<String> letterCombinations(String digits) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i <= digits.length() - 1; i++) {
            int intChar = digits.charAt(i);
            String intString = Character.toString(intChar);
            String letters = intMap.get(intString);

            List<String> temp = new ArrayList<>();
            for (int y = 0; y < letters.length(); y++) {
                int letterChar = letters.charAt(y);
                String letterString = Character.toString(letterChar);

                if (result.isEmpty()) {
                    temp.add(letterString);
                }
                for (String s : result) {
                    temp.add(s + letterString);
                }
            }
            result = temp;
        }
        return result;
    }

}
