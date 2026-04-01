package dev.nklip.javacraft.algs.leetcode.interview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * On a phone keypad, there is an association with numbers and letters (e.g. 9 can be associated with any of WXYZ letters).
 *
 * Please write a function which will accept in input a string containing 4 numbers and will
 * return the first matching 4 letter word from given set below, or empty string if no match found.
 * Presence of number 1 or 0 in a string will result in empty string result.
 *
 * Word set for matching: home, rise, exit, list, nine, game, cool, calm, tree, face
 *
 * E.g.
 * Input “4663” -> output “home”
 * Input “2120” -> output “”
 * Input “5555” -> output “”
 */
public class ButtonPhoneAutoCompletion {

    private final Map<String, String> digit2char = new LinkedHashMap<>(
            Map.of(
                    "2", "abc",
                    "3", "def",
                    "4", "ghi",
                    "5", "jkl",
                    "6", "mno",
                    "7", "pqrs",
                    "8", "tuv",
                    "9", "wxyz"
            )
    );

    public String findMatchingWord(int num, List<String> inputWords) {
        String numTxt = Integer.toString(num);

        // we exclude words by length
        List<String> words = new ArrayList<>();
        for (String word : inputWords) {
            if (word.length() == numTxt.length()) {
                words.add(word);
            }
        }

        // exclude number which contain 0 or 1
        for (int i = 0; i < numTxt.length(); i++) {
            String digit = Character.toString(numTxt.charAt(i));

            if (digit.equals("0") || digit.equals("1")) {
                return "";
            }
        }

        //  Input “4663” -> output “home” from home, rise, exit, list, nine, game, cool, calm, tree, face
        for (String word : words) {
            boolean isFailed = false;
            for (int i = 0; i < word.length(); i++) {
                String wordChar = Character.toString(word.charAt(i));

                String digit = Character.toString(numTxt.charAt(i));

                String digitPattern = digit2char.get(digit);

                if (!digitPattern.contains(wordChar)) {
                    isFailed = true;
                    break;
                }
            }
            if (!isFailed) {
                return word;
            }
        }
        return "";
    }

}
