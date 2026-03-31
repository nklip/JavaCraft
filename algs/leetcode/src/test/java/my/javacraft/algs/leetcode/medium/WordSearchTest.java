package my.javacraft.algs.leetcode.medium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WordSearchTest {

    @Test
    void testCase1() {
        char[][] board = {
                {'A', 'B', 'C', 'E'},
                {'S', 'F', 'C', 'S'},
                {'A', 'D', 'E', 'E'}
        };
        WordSearch solution = new WordSearch();

        Assertions.assertTrue(solution.exist(board,"ABCCED"));
    }

    @Test
    void testCase2() {
        char[][] board = {
                {'A', 'B', 'C', 'E'},
                {'S', 'F', 'C', 'S'},
                {'A', 'D', 'E', 'E'}
        };
        WordSearch solution = new WordSearch();

        Assertions.assertTrue(solution.exist(board,"SEE"));
    }

    @Test
    void testCase3() {
        char[][] board = {
                {'A', 'B', 'C', 'E'},
                {'S', 'F', 'C', 'S'},
                {'A', 'D', 'E', 'E'}
        };
        WordSearch solution = new WordSearch();

        Assertions.assertFalse(solution.exist(board,"ABCB"));
    }

    @Test
    void testCase4() {
        char[][] board = {
                {'A', 'B', 'C', 'E'},
                {'S', 'F', 'E', 'S'},
                {'A', 'D', 'E', 'E'}
        };
        WordSearch solution = new WordSearch();

        Assertions.assertTrue(solution.exist(board,"ABCESEEEFS"));
    }

    @Test
    void testCase5() {
        char[][] board = {
                {'A', 'A', 'A', 'A', 'A', 'A'},
                {'A', 'A', 'A', 'A', 'A', 'A'},
                {'A', 'A', 'A', 'A', 'A', 'A'},
                {'A', 'A', 'A', 'A', 'A', 'A'},
                {'A', 'A', 'A', 'A', 'A', 'A'},
                {'A', 'A', 'A', 'A', 'A', 'A'}
        };
        WordSearch solution = new WordSearch();

        Assertions.assertFalse(solution.exist(board,"AAAAAAAAAAAAAAB"));
    }
}
