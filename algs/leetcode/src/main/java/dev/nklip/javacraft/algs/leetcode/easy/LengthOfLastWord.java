package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 58. Length of Last Word
 * <p>
 * Given a string s consisting of words and spaces, return the length of the last word in the string.
 * <p>
 * A word is a maximal substring consisting of non-space characters only.
 */
public class LengthOfLastWord {

    public int lengthOfLastWord(String phraseStr) {
        int length = phraseStr.length();
        char []phrase = phraseStr.toCharArray();

        int startPos = -1;
        int endPos = phrase.length - 1;
        boolean isWord = false;

        for (int i = phrase.length - 1; i >= 0; i--) {
            char ch = phrase[i];

            if (ch == ' ') {
                if (isWord) {
                    startPos = i;
                    length = endPos - startPos;
                    break;
                } else {
                    endPos = i - 1;
                }
            } else {
                // there was at least a symbol from the end of the input string
                isWord = true;
            }
        }

        // if the right size of the string has empty symbols
        if (startPos == -1 && endPos != phrase.length - 1) {
            length = endPos + 1;
        }
        return length;
    }

}
