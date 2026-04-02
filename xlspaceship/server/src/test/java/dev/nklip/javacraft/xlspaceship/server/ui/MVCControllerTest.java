package dev.nklip.javacraft.xlspaceship.server.ui;

import dev.nklip.javacraft.xlspaceship.engine.game.BoardStatus;
import dev.nklip.javacraft.xlspaceship.engine.game.GameStatus;
import dev.nklip.javacraft.xlspaceship.engine.game.GameTurn;
import dev.nklip.javacraft.xlspaceship.engine.model.ErrorResponse;
import dev.nklip.javacraft.xlspaceship.engine.service.BoardHtmlRenderer;
import dev.nklip.javacraft.xlspaceship.engine.service.LocalPlayerService;
import dev.nklip.javacraft.xlspaceship.server.controller.MVCController;
import dev.nklip.javacraft.xlspaceship.server.http.ErrorResponses;
import dev.nklip.javacraft.xlspaceship.server.model.Pilot;
import dev.nklip.javacraft.xlspaceship.server.service.GameRequestValidationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.ModelAndView;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MVCControllerTest {

    @Test
    public void testGameIdWhenGameExistsProvidesBoardMarkupForTemplate() {
        String gameId = "match-1-3-6";
        LocalPlayerService localPlayerService = mock(LocalPlayerService.class);
        GameRequestValidationService gameRequestValidationService = mock(GameRequestValidationService.class);
        BoardHtmlRenderer boardHtmlRenderer = mock(BoardHtmlRenderer.class);

        GameStatus gameStatus = createGameStatus();
        when(gameRequestValidationService.validateGameIdExist(gameId)).thenReturn(null);
        when(gameRequestValidationService.getStatusByGameId(gameId)).thenReturn(gameStatus);
        when(boardHtmlRenderer.getBoardInHtml(gameStatus.getSelf(), false)).thenReturn("<table>my-board</table>");
        when(boardHtmlRenderer.getBoardInHtml(gameStatus.getOpponent(), true)).thenReturn("<table>opponent-board</table>");

        MVCController controller = new MVCController(localPlayerService, gameRequestValidationService, boardHtmlRenderer);

        ModelAndView modelAndView = controller.gameId(gameId);

        Assertions.assertNotNull(modelAndView);
        Assertions.assertEquals("game", modelAndView.getViewName());
        Assertions.assertEquals(gameStatus, modelAndView.getModel().get("gameStatus"));
        Assertions.assertEquals(gameId, modelAndView.getModel().get("gameId"));
        Assertions.assertEquals(5, modelAndView.getModel().get("aliveShips"));
        Assertions.assertEquals("<table>my-board</table>", modelAndView.getModel().get("myBoard"));
        Assertions.assertEquals("<table>opponent-board</table>", modelAndView.getModel().get("opponentBoard"));
    }

    @Test
    public void testGameIdWhenGameDoesNotExist() {
        String gameId = "match-1-3-6";
        LocalPlayerService localPlayerService = mock(LocalPlayerService.class);
        GameRequestValidationService gameRequestValidationService = mock(GameRequestValidationService.class);
        BoardHtmlRenderer boardHtmlRenderer = mock(BoardHtmlRenderer.class);

        when(localPlayerService.getUserId()).thenReturn("nikilipa");
        when(localPlayerService.getFullName()).thenReturn("Nikita Lipatov");
        when(gameRequestValidationService.validateGameIdExist(gameId)).thenReturn(gameNotFoundResponse(gameId));

        MVCController controller = new MVCController(localPlayerService, gameRequestValidationService, boardHtmlRenderer);

        ModelAndView modelAndView = controller.gameId(gameId);

        Assertions.assertNotNull(modelAndView);
        Assertions.assertEquals("index", modelAndView.getViewName());
        Assertions.assertEquals("Game ('match-1-3-6') is not found!", modelAndView.getModel().get("error"));
        Assertions.assertInstanceOf(Pilot.class, modelAndView.getModel().get("pilot"));

        Pilot pilot = (Pilot) modelAndView.getModel().get("pilot");
        Assertions.assertEquals("nikilipa", pilot.getUserId());
        Assertions.assertEquals("Nikita Lipatov", pilot.getFullName());
        verify(gameRequestValidationService, never()).getStatusByGameId(gameId);
    }

    private ResponseEntity<ErrorResponse> gameNotFoundResponse(String gameId) {
        return ErrorResponses.jsonError(
                GameRequestValidationService.GAME_NOT_FOUND.formatted(gameId),
                org.springframework.http.HttpStatus.BAD_REQUEST
        );
    }

    private GameStatus createGameStatus() {
        BoardStatus self = new BoardStatus();
        self.setUserId("nikilipa");

        BoardStatus opponent = new BoardStatus();
        opponent.setUserId("AI");

        GameTurn gameTurn = new GameTurn();
        gameTurn.setPlayerTurn("nikilipa");

        GameStatus gameStatus = new GameStatus();
        gameStatus.setSelf(self);
        gameStatus.setOpponent(opponent);
        gameStatus.setGameTurn(gameTurn);
        gameStatus.setAliveShips(5);
        return gameStatus;
    }
}
