package my.javacraft.algs.leetcode.medium;

import java.util.HashSet;
import java.util.Set;

/*
 * 79. Word Search
 *
 * LeetCode: https://leetcode.com/problems/word-search/description/
 *
 * Given an m x n grid of characters board and a string word, return true if word exists in the grid.
 *
 * The word can be constructed from letters of sequentially adjacent cells,
 * where adjacent cells are horizontally or vertically neighboring.
 * The same letter cell may not be used more than once.
 *
 * For example:
 * ┌───┬───┬───┬───┐
 * │ A │ B │ C │ E │
 * ├───┼───┼───┼───┤
 * │ S │ F │ C │ S │
 * ├───┼───┼───┼───┤
 * │ A │ D │ E │ E │
 * └───┴───┴───┴───┘
 */
public class WordSearch {
    public boolean exist(final char[][] board, final String word) {

        for (int row = 0; row < board.length; row++) {
            for (int col = 0; col < board[row].length; col++) {
                char ch = board[row][col];
                if (word.charAt(0) == ch) {
                    Set<String> checkedCells = new HashSet<>();
                    boolean exists = findWord(row, col, board, checkedCells, word, 0);
                    if (exists) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean findWord(
            final int row, final int col,
            final char[][]board, final Set<String> checkedCells,
            final String word, final int wordIndex) {
        // check for being beyond board borders
        if (row < 0 || row > board.length - 1) {
            return false;
        }
        // check for being beyond board borders
        if (col < 0 || col > board[row].length - 1) {
            return false;
        }
        // check for being beyond word length
        if (wordIndex == word.length()) {
            return false;
        }
        // if chars are not equals not point to continue
        if (word.charAt(wordIndex) != board[row][col]) {
            return false;
        }
        String cellId = row + "_" + col;
        if (checkedCells.contains(cellId)) {
            return false;
        }
        // word is found
        if (word.charAt(wordIndex) == board[row][col] && wordIndex + 1 == word.length()) {
            return true;
        }
        // added this cell into checked cells
        checkedCells.add(cellId);

        boolean exists;
        // check left
        exists = findWord(row + 1, col, board, checkedCells, word, wordIndex + 1);
        if (exists) {
            return true;
        }
        // check right
        exists = findWord(row - 1, col, board, checkedCells, word, wordIndex + 1);
        if (exists) {
            return true;
        }
        // check up
        exists = findWord(row, col + 1, board, checkedCells, word, wordIndex + 1);
        if (exists) {
            return true;
        }
        // check down
        exists = findWord(row, col - 1, board, checkedCells, word, wordIndex + 1);
        if (exists) {
            return true;
        }
        checkedCells.remove(cellId);
        return false;
    }

}
