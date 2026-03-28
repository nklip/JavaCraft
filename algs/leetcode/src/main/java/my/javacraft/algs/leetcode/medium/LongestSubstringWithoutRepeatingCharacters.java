package my.javacraft.algs.leetcode.medium;

/*
 * 3. Longest Substring Without Repeating Characters
 *
 * LeetCode: https://leetcode.com/problems/longest-substring-without-repeating-characters
 *
 * Given a string s, find the length of the longest substring without duplicate characters.
 */
public class LongestSubstringWithoutRepeatingCharacters {

    // using array
    public int lengthOfLongestSubstring(String inputString) {
        int longest = 0;

        int current = 0;
        char[] charArray = new char[inputString.length()];
        for (int i = 0; i < inputString.length(); i++) {
            char ch = inputString.charAt(i);

            if (contains(charArray, current, ch)) {
                int index = indexOf(charArray, current, ch) + 1;
                delete(charArray, index, current);

                current -= index;
                charArray[current++] = ch;
            } else {
                charArray[current++] = ch;

                longest = Math.max(current, longest);
            }
        }
        return longest;
    }

    private boolean contains(char[] charArray, int current, char ch) {
        for (int i = 0; i < current; i++) {
            if (charArray[i] == ch) {
                return true;
            }
        }
        return false;
    }

    private int indexOf(char[] charArray, int current, char ch) {
        for (int i = 0; i < current; i++) {
            if (charArray[i] == ch) {
                return i;
            }
        }
        return -1;
    }

    // start - 1; end - 4
    private void delete(char[] charArray, int start, int current) {
        for (int i = 0; i < current; i++) {
            if (start + i >= charArray.length) {
                break;
            }
            charArray[i] = charArray[start + i];
        }
    }

    // using StringBuilder
    public int lengthOfLongestSubstring2(String inputString) {
        int longest = 0;
        StringBuilder temp = new StringBuilder();
        for (int i = 0; i < inputString.length(); i++) {
            char ch = inputString.charAt(i);
            String chStr = Character.toString(ch);

            if (temp.toString().contains(chStr)) {
                int start = temp.indexOf(chStr);
                temp.delete(0, start + 1);
                temp.append(chStr);
            } else {
                temp.append(chStr);
                longest = Math.max(temp.length(), longest);
            }
        }
        return longest;
    }

}
