package dev.nklip.javacraft.xlspaceship.server.service;

import dev.nklip.javacraft.xlspaceship.engine.game.GameStatus;
import dev.nklip.javacraft.xlspaceship.engine.model.ErrorResponse;
import dev.nklip.javacraft.xlspaceship.engine.model.SalvoRequest;
import dev.nklip.javacraft.xlspaceship.engine.service.GameSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import static dev.nklip.javacraft.xlspaceship.server.http.ErrorResponses.jsonError;

@Service
@RequiredArgsConstructor
public class GameRequestValidationService {

    public static final String GAME_ALREADY_EXISTS = "Such game = '%s' already exists.";
    public static final String MORE_THEN_5 = "More then 5 shots!";
    public static final String WRONG_TURN = "It's not your turn.";
    public static final String WRONG_SHOT_FORMAT = "Wrong format. Shot = '%s'.";
    public static final String WRONG_PORT_FORMAT = "Port should consist from numbers ('%s').";
    public static final String GAME_NOT_FOUND = "Game ('%s') is not found!";

    private static final Object lockShotByOpponent = new Object();
    private static final Object lockShotByMyself = new Object();

    private final GameSessionService xl;

    public ResponseEntity<ErrorResponse> validateGameIdExist(String gameId) {
        boolean isGameExist = xl.isGameIdExist(gameId);
        return isGameExist ? null : jsonError(
                GAME_NOT_FOUND.formatted(gameId),
                HttpStatus.BAD_REQUEST
        );
    }

    public ResponseEntity<?> validateFireRequest(SalvoRequest salvoRequest) {
        if (salvoRequest.getSalvo().size() > 5) {
            return jsonError(MORE_THEN_5, HttpStatus.BAD_REQUEST);
        }
        for (String shot : salvoRequest.getSalvo()) {
            if (shot.length() != 3) {
                return jsonError(String.format(WRONG_SHOT_FORMAT, shot), HttpStatus.BAD_REQUEST);
            }
            int x = parse2radix10(shot.substring(0, 1));
            if (x < 0 || x > 15) {
                return jsonError(String.format(WRONG_SHOT_FORMAT, shot), HttpStatus.BAD_REQUEST);
            }
            String temp2 = shot.substring(1, 2);
            if (!temp2.equalsIgnoreCase("x")) {
                return jsonError(String.format(WRONG_SHOT_FORMAT, shot), HttpStatus.BAD_REQUEST);
            }
            int y = parse2radix10(shot.substring(2, 3));
            if (y < 0 || y > 15) {
                return jsonError(String.format(WRONG_SHOT_FORMAT, shot), HttpStatus.BAD_REQUEST);
            }
        }
        return null;
    }

    private int parse2radix10(String value) {
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    public ResponseEntity<?> validateRemotePort(String port) {
        try {
            Integer.parseInt(port);
        } catch (NumberFormatException nfe) {
            return jsonError(String.format(WRONG_PORT_FORMAT, port), HttpStatus.BAD_REQUEST);
        }
        return null;
    }

    public GameStatus getStatusByGameId(String gameId) {
        return xl.status(gameId);
    }

    public ResponseEntity<?> fireFromEnemy(String gameId, SalvoRequest salvoRequestFromOpponent) {
        ResponseEntity<ErrorResponse> gameNotFoundResponse = validateGameIdExist(gameId);
        if (gameNotFoundResponse != null) {
            return gameNotFoundResponse;
        }
        boolean turnIsAllowed = xl.isOpponentTurn(gameId);
        if (turnIsAllowed) {
            synchronized (lockShotByOpponent) {
                turnIsAllowed = xl.isOpponentTurn(gameId);
                if (turnIsAllowed) {
                    return new ResponseEntity<>(
                            xl.fireFromEnemy(gameId, salvoRequestFromOpponent),
                            HttpStatus.OK
                    );
                }
            }
        }
        return jsonError(WRONG_TURN, HttpStatus.BAD_REQUEST);
    }

    public ResponseEntity<?> fireInEnemy(String gameId, SalvoRequest salvoRequestFromOpponent) {
        ResponseEntity<ErrorResponse> gameNotFoundResponse = validateGameIdExist(gameId);
        if (gameNotFoundResponse != null) {
            return gameNotFoundResponse;
        }
        boolean turnIsAllowed = xl.isMyselfTurn(gameId);
        if (turnIsAllowed) {
            synchronized (lockShotByMyself) {
                turnIsAllowed = xl.isMyselfTurn(gameId);
                if (turnIsAllowed) {
                    return new ResponseEntity<>(
                            xl.fireInEnemy(gameId, salvoRequestFromOpponent),
                            HttpStatus.OK
                    );
                }
            }
        }
        return jsonError(WRONG_TURN, HttpStatus.BAD_REQUEST);
    }
}
