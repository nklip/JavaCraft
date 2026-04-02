package dev.nklip.javacraft.xlspaceship.engine.service;

import dev.nklip.javacraft.xlspaceship.engine.game.Board;
import dev.nklip.javacraft.xlspaceship.engine.game.BoardStatus;
import dev.nklip.javacraft.xlspaceship.engine.game.ships.Winger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BoardHtmlRendererTest {

    @Test
    void testGetBoardInHtmlNonClickable() {
        BoardHtmlRenderer boardHtmlRenderer = new BoardHtmlRenderer();
        BoardStatus boardStatus = createBoardStatus();

        String html = boardHtmlRenderer.getBoardInHtml(boardStatus, false);

        Assertions.assertNotNull(html);
        Assertions.assertTrue(html.startsWith("<table><tr class=\"row\">"));
        Assertions.assertTrue(html.contains("<td class=\"ship\"/>"));
        Assertions.assertTrue(html.contains("<td class=\"sunk\"/>"));
        Assertions.assertTrue(html.contains("<td class=\"shot\"/>"));
        Assertions.assertTrue(html.contains("<td class=\"empty\"/>"));
        Assertions.assertFalse(html.contains("onclick=\"addShot(this);\""));
    }

    @Test
    void testGetBoardInHtmlClickable() {
        BoardHtmlRenderer boardHtmlRenderer = new BoardHtmlRenderer();
        BoardStatus boardStatus = createBoardStatus();

        String html = boardHtmlRenderer.getBoardInHtml(boardStatus, true);

        Assertions.assertNotNull(html);
        Assertions.assertTrue(html.contains("id=\"0x0\""));
        Assertions.assertTrue(html.contains("shot=\"0x0\""));
        Assertions.assertTrue(html.contains("onclick=\"addShot(this);\""));
    }

    private BoardStatus createBoardStatus() {
        Board board = new Board();
        board.printSpaceship(0, 0, new Winger(1));
        Assertions.assertEquals(Board.MISS, board.shot(5, 5));
        Assertions.assertEquals(Board.HIT, board.shot(0, 0));

        BoardStatus boardStatus = new BoardStatus();
        boardStatus.setBoard(board);
        return boardStatus;
    }
}
