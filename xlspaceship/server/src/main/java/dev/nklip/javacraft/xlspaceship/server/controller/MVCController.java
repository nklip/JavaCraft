package dev.nklip.javacraft.xlspaceship.server.controller;

import dev.nklip.javacraft.xlspaceship.engine.model.ErrorResponse;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.xlspaceship.engine.game.GameStatus;
import dev.nklip.javacraft.xlspaceship.engine.game.GameTurn;
import dev.nklip.javacraft.xlspaceship.engine.service.BoardHtmlRenderer;
import dev.nklip.javacraft.xlspaceship.engine.service.LocalPlayerService;
import dev.nklip.javacraft.xlspaceship.server.model.Pilot;
import dev.nklip.javacraft.xlspaceship.server.service.GameRequestValidationService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping("/")
@RequiredArgsConstructor
public class MVCController {

    private final LocalPlayerService localPlayerService;
    private final GameRequestValidationService gameRequestValidationService;
    private final BoardHtmlRenderer boardHtmlRenderer;

    @GetMapping
    public ModelAndView index() {
        return createIndexView(null);
    }

    private ModelAndView createIndexView(String errorMessage) {
        Pilot pilot = new Pilot();
        pilot.setUserId(localPlayerService.getUserId());
        pilot.setFullName(localPlayerService.getFullName());
        ModelAndView modelAndView = new ModelAndView("index", "pilot", pilot);
        if (errorMessage != null) {
            modelAndView.addObject("error", errorMessage);
        }
        return modelAndView;
    }

    @GetMapping("gameId/{gameId}")
    public ModelAndView gameId(@PathVariable("gameId") String gameId) {
        ResponseEntity<ErrorResponse> gameNotFoundResponse = gameRequestValidationService.validateGameIdExist(gameId);
        if (gameNotFoundResponse != null) {
            ErrorResponse errorResponse = gameNotFoundResponse.getBody();
            String errorMessage = (errorResponse != null)
                    ? errorResponse.getError()
                    : GameRequestValidationService.GAME_NOT_FOUND.formatted(gameId);
            return createIndexView(errorMessage);
        }

        GameStatus gameStatus = gameRequestValidationService.getStatusByGameId(gameId);

        GameTurn gameTurn = gameStatus.getGameTurn();

        boolean isClickable = true;
        String attrName  = (gameTurn.getPlayerTurn() != null) ? "playerTurn" : "won";
        if (attrName.equalsIgnoreCase("won")) {
            isClickable = false;
        }
        String attrValue = (gameTurn.getPlayerTurn() != null)
                ? gameTurn.getPlayerTurn()
                : gameTurn.getWon();
        return new ModelAndView(
                "game",
                "gameStatus",
                gameStatus
        ).addObject(attrName, attrValue
        ).addObject("gameId", gameId
        ).addObject("aliveShips", gameStatus.getAliveShips()
        ).addObject("myBoard", boardHtmlRenderer.getBoardInHtml(gameStatus.getSelf(), false)
        ).addObject("opponentBoard", boardHtmlRenderer.getBoardInHtml(gameStatus.getOpponent(), isClickable)
        );
    }



}
