package dev.nklip.javacraft.xlspaceship.impl.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.xlspaceship.impl.game.GameStatus;
import dev.nklip.javacraft.xlspaceship.impl.game.GameTurn;
import dev.nklip.javacraft.xlspaceship.impl.game.Grid;
import dev.nklip.javacraft.xlspaceship.impl.game.GridStatus;
import dev.nklip.javacraft.xlspaceship.impl.model.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class XLSpaceshipServices {

    private static final ConcurrentHashMap<String, GameStatus> games = new ConcurrentHashMap<>();

    private final UserServices userServices;
    private final GridServices gridServices;
    private final GameServices gameServices;
    private final RestServices restServices;
    private final AIServices aiServices;

    public NewGameResponse createRemoteGame(NewGameRequest newGameRequest) {
        String localUserId = newGameRequest.getUserId();
        String remoteHost = newGameRequest.getSpaceshipProtocol().getHostname();
        String remotePort = newGameRequest.getSpaceshipProtocol().getPort();

        String remoteUserId = userServices.getUserId();
        String gameId = gameServices.nextUniqueGameId();

        Grid opponentGrid = gridServices.newUnknownGrid();
        Grid myGrid = gridServices.newRandomGrid();
        String playerTurn = gameServices.chooseStartingPlayer(localUserId, remoteUserId);

        GridStatus self = new GridStatus();
        self.setUserId(remoteUserId);
        self.setGrid(myGrid);

        GridStatus opponent = new GridStatus();
        opponent.setUserId(localUserId);
        opponent.setGrid(opponentGrid);

        GameTurn gameTurn = new GameTurn();
        gameTurn.setPlayerTurn(playerTurn);

        int aliveShips = myGrid.getSpaceshipList().size();
        GameStatus gameStatus = new GameStatus();
        gameStatus.setHost(remoteHost);
        gameStatus.setPort(Integer.parseInt(remotePort));
        gameStatus.setAliveShips(aliveShips);
        gameStatus.setSelf(self);
        gameStatus.setOpponent(opponent);
        gameStatus.setGameTurn(gameTurn);

        games.put(gameId, gameStatus); // save instance

        NewGameResponse newGameResponse = new NewGameResponse();
        newGameResponse.setUserId(remoteUserId);
        newGameResponse.setFullName(userServices.getFullName());
        newGameResponse.setGameId(gameId);
        newGameResponse.setStarting(playerTurn);

        aiServices.asyncFireRequest(playerTurn, gameId, aliveShips, opponent);

        return newGameResponse;
    }

    public void createLocalGame(String remoteHost, int remotePort, String uniqueGameId, NewGameResponse newGameResponse) {
        String opponentId = newGameResponse.getUserId();
        String playerTurn = newGameResponse.getStarting();

        GridStatus self = new GridStatus();
        Grid myGrid = gridServices.newRandomGrid();
        self.setUserId(userServices.getUserId());
        self.setGrid(myGrid);

        Grid unknownGrid = gridServices.newUnknownGrid();
        GridStatus opponent = new GridStatus();
        opponent.setUserId(opponentId);
        opponent.setGrid(unknownGrid);

        GameTurn gameTurn = new GameTurn();
        gameTurn.setPlayerTurn(playerTurn);

        GameStatus gameStatus = new GameStatus();
        gameStatus.setHost(remoteHost);
        gameStatus.setPort(remotePort);
        gameStatus.setAliveShips(myGrid.getSpaceshipList().size());
        gameStatus.setSelf(self);
        gameStatus.setOpponent(opponent);
        gameStatus.setGameTurn(gameTurn);

        games.put(uniqueGameId, gameStatus); // save instance
    }

    public boolean isGameIdExist(String gameId) {
        return games.containsKey(gameId);
    }

    public GameStatus status(String gameId) {
        return games.get(gameId);
    }

    public boolean isOpponentTurn(String gameId) {
        GameStatus gameStatus = games.get(gameId);
        String playerTurn = gameStatus.getGameTurn().getPlayerTurn();
        String opponentId = gameStatus.getOpponent().getUserId();
        return opponentId.equalsIgnoreCase(playerTurn);
    }

    public boolean isMyselfTurn(String gameId) {
        GameStatus gameStatus = games.get(gameId);
        String playerTurn = gameStatus.getGameTurn().getPlayerTurn();
        String myselfId = userServices.getUserId();
        return myselfId.equalsIgnoreCase(playerTurn);
    }

    public FireResponse fireFromEnemy(String gameId, FireRequest fireRequestByOpponent) {
        GameStatus gameStatus = games.get(gameId);

        Grid myGrid = gameStatus.getSelf().getGrid();
        GridStatus opponentGrid = gameStatus.getOpponent();

        int aliveShips = gameStatus.getAliveShips();
        Map<String, String> shots = new LinkedHashMap<>();
        for (String shot : fireRequestByOpponent.getSalvo()) {
            int x = Integer.parseInt(shot.substring(0, 1), 16);
            int y = Integer.parseInt(shot.substring(2, 3), 16);

            String status = myGrid.shot(x, y);
            shots.put(shot, status);
            if (status.equalsIgnoreCase(Grid.KILL)) {
                aliveShips--;
            }
        }

        FireResponse fireResponse = new FireResponse();
        fireResponse.setSalvo(shots);

        GameTurn turn = new GameTurn();

        String playerTurn = userServices.getUserId();
        if (aliveShips == 0) {
            turn.setWon(gameStatus.getOpponent().getUserId());
        } else {
            turn.setPlayerTurn(playerTurn);
        }
        gameStatus.setGameTurn(turn);
        gameStatus.setAliveShips(aliveShips);
        fireResponse.setGameTurn(turn);

        aiServices.asyncFireRequest(playerTurn, gameId, aliveShips, opponentGrid);
        
        return fireResponse;
    }

    public FireResponse fireInEnemy(String gameId, FireRequest fireRequestByMyself) {
        GameStatus gameStatus = games.get(gameId);

        Grid opponentGrid = gameStatus.getOpponent().getGrid();

        FireResponse fireResponse = executeShots(gameId, fireRequestByMyself);

        for (Map.Entry<String, String> entry : fireResponse.getSalvo().entrySet()) {
            String key = entry.getKey();

            int x = Integer.parseInt(key.substring(0, 1), 16);
            int y = Integer.parseInt(key.substring(2, 3), 16);

            opponentGrid.update(x, y, entry.getValue());
        }

        GameTurn turn = new GameTurn();
        String playerTurn = gameStatus.getOpponent().getUserId();
        if (fireResponse.getGameTurn().getWon() != null) {
            turn.setWon(fireResponse.getGameTurn().getWon());
        } else {
            turn.setPlayerTurn(playerTurn);
        }

        gameStatus.setGameTurn(turn);
        fireResponse.setGameTurn(turn);
        
        return fireResponse;
    }

    private FireResponse executeShots(String gameId, FireRequest fireRequest) {
        GameStatus gameStatus = games.get(gameId);
        String remoteHost = gameStatus.getHost();
        int removePort = gameStatus.getPort();

        return restServices.fireShot(remoteHost, removePort, gameId, fireRequest);
    }

}
