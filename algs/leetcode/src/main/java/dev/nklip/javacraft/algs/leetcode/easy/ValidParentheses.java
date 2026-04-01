package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 20. Valid Parentheses
 * <p>
 * Given a string s containing just the characters '(', ')', '{', '}', '[' and ']', determine if the input string is valid.
 * <p>
 * An input string is valid if:
 * <p>
 * 1. Open brackets must be closed by the same type of brackets.
 * 2. Open brackets must be closed in the correct order.
 * 3. Every close bracket has a corresponding open bracket of the same type.
 */
public class ValidParentheses {

    public boolean isValid(String inputString) {
        char []inputChars = inputString.toCharArray();

        char []tempChars = new char[inputChars.length];
        int tempPos = 0;

        for (char currCh : inputChars) {
            tempChars[tempPos++] = currCh;

            if (tempPos > 1) {
                char lastCh = tempChars[tempPos - 2];

                if (lastCh == '(' && currCh == ')') {
                    tempChars[--tempPos] = 'N';
                    tempChars[--tempPos] = 'N';
                } else if (lastCh == '{' && currCh == '}') {
                    tempChars[--tempPos] = 'N';
                    tempChars[--tempPos] = 'N';
                } else if (lastCh == '[' && currCh == ']') {
                    tempChars[--tempPos] = 'N';
                    tempChars[--tempPos] = 'N';
                }

                if (lastCh == '(' && (currCh == '}' || currCh == ']')) {
                    return false;
                } else if (lastCh == '{' && (currCh == ')' || currCh == ']')) {
                    return false;
                } else if (lastCh == '[' && (currCh == '}' || currCh == ')')) {
                    return false;
                }
            }
        }

        return tempPos == 0;
    }

}
