package dev.nklip.javacraft.xlspaceship.engine.service;

import dev.nklip.javacraft.xlspaceship.engine.game.Board;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.xlspaceship.engine.game.GameStatus;
import dev.nklip.javacraft.xlspaceship.engine.game.GameTurn;
import dev.nklip.javacraft.xlspaceship.engine.game.BoardStatus;
import dev.nklip.javacraft.xlspaceship.engine.model.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameSessionService {

    private static final ConcurrentHashMap<String, GameStatus> games = new ConcurrentHashMap<>();

    private final LocalPlayerService localPlayerService;
    private final BoardFactory boardFactory;
    private final GameSetupService gameSetupService;
    private final RemoteGameClient remoteGameClient;
    private final AiTurnService aiTurnService;

    public NewGameResponse createRemoteGame(NewGameRequest newGameRequest) {
        String localUserId = newGameRequest.getUserId();
        String remoteHost = newGameRequest.getSpaceshipProtocol().getHostname();
        String remotePort = newGameRequest.getSpaceshipProtocol().getPort();

        String remoteUserId = localPlayerService.getUserId();
        String gameId = gameSetupService.nextUniqueGameId();

        Board opponentBoard = boardFactory.newUnknownBoard();
        Board myBoard = boardFactory.newRandomBoard();
        String playerTurn = gameSetupService.chooseStartingPlayer(localUserId, remoteUserId);

        BoardStatus self = new BoardStatus();
        self.setUserId(remoteUserId);
        self.setBoard(myBoard);

        BoardStatus opponent = new BoardStatus();
        opponent.setUserId(localUserId);
        opponent.setBoard(opponentBoard);

        GameTurn gameTurn = new GameTurn();
        gameTurn.setPlayerTurn(playerTurn);

        int aliveShips = myBoard.getSpaceships().size();
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
        newGameResponse.setFullName(localPlayerService.getFullName());
        newGameResponse.setGameId(gameId);
        newGameResponse.setStarting(playerTurn);

        aiTurnService.asyncFireRequest(playerTurn, gameId, aliveShips, opponent);

        return newGameResponse;
    }

    public void createLocalGame(String remoteHost, int remotePort, String uniqueGameId, NewGameResponse newGameResponse) {
        String opponentId = newGameResponse.getUserId();
        String playerTurn = newGameResponse.getStarting();

        BoardStatus self = new BoardStatus();
        Board myBoard = boardFactory.newRandomBoard();
        self.setUserId(localPlayerService.getUserId());
        self.setBoard(myBoard);

        Board unknownBoard = boardFactory.newUnknownBoard();
        BoardStatus opponent = new BoardStatus();
        opponent.setUserId(opponentId);
        opponent.setBoard(unknownBoard);

        GameTurn gameTurn = new GameTurn();
        gameTurn.setPlayerTurn(playerTurn);

        GameStatus gameStatus = new GameStatus();
        gameStatus.setHost(remoteHost);
        gameStatus.setPort(remotePort);
        gameStatus.setAliveShips(myBoard.getSpaceships().size());
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
        String myselfId = localPlayerService.getUserId();
        return myselfId.equalsIgnoreCase(playerTurn);
    }

    public SalvoResponse fireFromEnemy(String gameId, SalvoRequest salvoRequestByOpponent) {
        GameStatus gameStatus = games.get(gameId);

        Board myBoard = gameStatus.getSelf().getBoard();
        BoardStatus opponentStatus = gameStatus.getOpponent();

        int aliveShips = gameStatus.getAliveShips();
        Map<String, String> shots = new LinkedHashMap<>();
        for (String shot : salvoRequestByOpponent.getSalvo()) {
            int x = Integer.parseInt(shot.substring(0, 1), 16);
            int y = Integer.parseInt(shot.substring(2, 3), 16);

            String status = myBoard.shot(x, y);
            shots.put(shot, status);
            if (status.equalsIgnoreCase(Board.KILL)) {
                aliveShips--;
            }
        }

        SalvoResponse salvoResponse = new SalvoResponse();
        salvoResponse.setSalvo(shots);

        GameTurn turn = new GameTurn();

        String playerTurn = localPlayerService.getUserId();
        if (aliveShips == 0) {
            turn.setWon(gameStatus.getOpponent().getUserId());
        } else {
            turn.setPlayerTurn(playerTurn);
        }
        gameStatus.setGameTurn(turn);
        gameStatus.setAliveShips(aliveShips);
        salvoResponse.setGameTurn(turn);

        aiTurnService.asyncFireRequest(playerTurn, gameId, aliveShips, opponentStatus);
        
        return salvoResponse;
    }

    public SalvoResponse fireInEnemy(String gameId, SalvoRequest salvoRequestByMyself) {
        GameStatus gameStatus = games.get(gameId);

        Board opponentBoard = gameStatus.getOpponent().getBoard();

        SalvoResponse salvoResponse = executeShots(gameId, salvoRequestByMyself);

        for (Map.Entry<String, String> entry : salvoResponse.getSalvo().entrySet()) {
            String key = entry.getKey();

            int x = Integer.parseInt(key.substring(0, 1), 16);
            int y = Integer.parseInt(key.substring(2, 3), 16);

            opponentBoard.update(x, y, entry.getValue());
        }

        GameTurn turn = new GameTurn();
        String playerTurn = gameStatus.getOpponent().getUserId();
        if (salvoResponse.getGameTurn().getWon() != null) {
            turn.setWon(salvoResponse.getGameTurn().getWon());
        } else {
            turn.setPlayerTurn(playerTurn);
        }

        gameStatus.setGameTurn(turn);
        salvoResponse.setGameTurn(turn);
        
        return salvoResponse;
    }

    private SalvoResponse executeShots(String gameId, SalvoRequest salvoRequest) {
        GameStatus gameStatus = games.get(gameId);
        String remoteHost = gameStatus.getHost();
        int removePort = gameStatus.getPort();

        return remoteGameClient.fireShot(remoteHost, removePort, gameId, salvoRequest);
    }

}
