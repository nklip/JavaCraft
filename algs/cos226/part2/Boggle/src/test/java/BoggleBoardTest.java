import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class BoggleBoardTest {

    @Test
    void testBuildsBoardFromCharacterArray() {
        char[][] letters = {
                {'A', 'Q'},
                {'B', 'C'}
        };

        BoggleBoard board = new BoggleBoard(letters);
        letters[0][0] = 'Z';

        Assertions.assertEquals(2, board.rows());
        Assertions.assertEquals(2, board.cols());
        Assertions.assertEquals('A', board.getLetter(0, 0));
        Assertions.assertEquals('Q', board.getLetter(0, 1));
        Assertions.assertEquals("2 2\nA  Qu \nB  C", board.toString());
    }

    @Test
    void testReadsBoardFromResource() {
        BoggleBoard board = new BoggleBoard("board4x4.txt");

        Assertions.assertEquals(4, board.rows());
        Assertions.assertEquals(4, board.cols());
        Assertions.assertEquals('A', board.getLetter(0, 0));
        Assertions.assertEquals('E', board.getLetter(3, 3));
    }

    @Test
    void testCreatesBoardsWithRequestedDimensions() {
        BoggleBoard board = new BoggleBoard(3, 5);

        Assertions.assertEquals(3, board.rows());
        Assertions.assertEquals(5, board.cols());
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                Assertions.assertTrue(board.getLetter(row, col) >= 'A');
                Assertions.assertTrue(board.getLetter(row, col) <= 'Z');
            }
        }
    }

    @Test
    void testRejectsInvalidCharacterArrays() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new BoggleBoard(new char[][]{{'A', 'B'}, {'C'}})
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new BoggleBoard(new char[][]{{'A', '1'}})
        );
    }

    /** A cell reading {@code Qu} is stored as the single letter {@code Q}. */
    @Test
    void testReadsAQuCellFromAFileAsASingleQ() {
        BoggleBoard board = new BoggleBoard("board-q.txt");

        Assertions.assertEquals('Q', board.getLetter(2, 1));
        Assertions.assertTrue(board.toString().contains("Qu"));
    }

    /**
     * The file constructor rejects a cell in two separate ways - anything longer than one
     * character that is not {@code Qu}, and any single character outside A to Z. Nothing shipped
     * is malformed, so the files are written here.
     */
    @Test
    void testRejectsMalformedCellsInABoardFile(@TempDir Path directory) throws IOException {
        Path tooLong = Files.writeString(directory.resolve("too-long.txt"), "1 2\nA ABC\n");
        Path notALetter = Files.writeString(directory.resolve("not-a-letter.txt"), "1 2\nA 7\n");

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new BoggleBoard(tooLong.toAbsolutePath().toString()),
                "a multi-character cell that is not Qu"
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new BoggleBoard(notALetter.toAbsolutePath().toString()),
                "a single character outside A-Z"
        );
    }

    /** The no-argument constructor deals the 1992 Hasbro dice, so it is always four by four. */
    @Test
    void testTheHasbroBoardIsAlwaysFourByFourOfLetters() {
        for (int attempt = 0; attempt < 5; attempt++) {
            BoggleBoard board = new BoggleBoard();

            Assertions.assertEquals(4, board.rows());
            Assertions.assertEquals(4, board.cols());
            for (int row = 0; row < board.rows(); row++) {
                for (int column = 0; column < board.cols(); column++) {
                    char letter = board.getLetter(row, column);
                    Assertions.assertTrue(letter >= 'A' && letter <= 'Z', "letter " + letter);
                }
            }
        }
    }

    /**
     * Only that it runs. {@code main} prints through {@code StdOut}, which copies
     * {@code System.out} once in its static initializer, so capturing the output would depend on
     * class load order.
     */
    @Test
    void testMainRunsToCompletion() {
        Assertions.assertDoesNotThrow(() -> BoggleBoard.main(new String[0]));
    }
}
