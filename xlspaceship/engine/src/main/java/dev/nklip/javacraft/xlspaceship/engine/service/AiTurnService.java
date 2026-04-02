package dev.nklip.javacraft.xlspaceship.engine.service;

import dev.nklip.javacraft.xlspaceship.engine.game.Board;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.xlspaceship.engine.game.BoardStatus;
import dev.nklip.javacraft.xlspaceship.engine.model.SalvoRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTurnService {

    private static final ExecutorService executors = Executors.newFixedThreadPool(10);

    private final LocalPlayerService localPlayerService;
    private final RandomProvider randomProvider;
    private final RemoteGameClient remoteGameClient;

    public void asyncFireRequest(final String playerTurn, final String gameId, final int aliveShips, final BoardStatus opponent) {
        if (localPlayerService.isAI() && localPlayerService.getUserId().equalsIgnoreCase(playerTurn) && aliveShips > 0) {
            executors.execute(() -> {
//                try {
                    //Thread.sleep(500L);

                    SalvoRequest salvoRequest = new SalvoRequest();
                    salvoRequest.setSalvo(addSalvo(opponent, aliveShips));

                    String localHost = remoteGameClient.getCurrentHostname();
                    int localPort = remoteGameClient.getCurrentPort();

                    log.debug("host = {} port = {} gameId = {} aliveShips = {} request = {}", localHost, localPort, gameId, aliveShips, salvoRequest);

                    remoteGameClient.fireShotByAi(localHost, localPort, gameId, salvoRequest);
//                } catch (InterruptedException ie) {
//                    log.error(ie.getMessage());
//                }
            });
        }
    }

    private List<String> addSalvo(final BoardStatus opponent, int aliveShips) {
        Set<String> salvo = new LinkedHashSet<>();
        for (int i = 1; i <= aliveShips; i++) {
            String shot = shoot(opponent, salvo);
            if (shot == null) {
                break;
            }
            salvo.add(shot);
        }
        return new ArrayList<>(salvo);
    }

    // generate
    private String shoot(final BoardStatus opponent, Set<String> salvo) {
        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                String cellValue = opponent.getBoard().getValue(x, y);

                // if we have a damaged ship...
                if (cellValue.equalsIgnoreCase("X")) {
                    String shot;
                    shot = getTopLeftCell(x, y, opponent, salvo);
                    if (shot != null) {
                        return shot;
                    }
                    shot = getTopMediumCell(x, y, opponent, salvo);
                    if (shot != null) {
                        return shot;
                    }
                    shot = getTopRightCell(x, y, opponent, salvo);
                    if (shot != null) {
                        return shot;
                    }
                    shot = getLeftCell(x, y, opponent, salvo);
                    if (shot != null) {
                        return shot;
                    }
                    shot = getRightCell(x, y, opponent, salvo);
                    if (shot != null) {
                        return shot;
                    }
                    shot = getBottomLeftCell(x, y, opponent, salvo);
                    if (shot != null) {
                        return shot;
                    }
                    shot = getBottomMediumCell(x, y, opponent, salvo);
                    if (shot != null) {
                        return shot;
                    }
                    shot = getBottomRightCell(x, y, opponent, salvo);
                    if (shot != null) {
                        return shot;
                    }
                }
            }
        }
        return fireRandomly(opponent, salvo);
    }

    private String getTopLeftCell(int x, int y, BoardStatus opponent, Set<String> salvo) {
        return checkShot(x - 1, y - 1, opponent, salvo);
    }

    private String getTopMediumCell(int x, int y, BoardStatus opponent, Set<String> salvo) {
        return checkShot(x, y - 1, opponent, salvo);
    }

    private String getTopRightCell(int x, int y, BoardStatus opponent, Set<String> salvo) {
        return checkShot(x + 1, y - 1, opponent, salvo);
    }

    private String getLeftCell(int x, int y, BoardStatus opponent, Set<String> salvo) {
        return checkShot(x - 1, y, opponent, salvo);
    }

    private String getRightCell(int x, int y, BoardStatus opponent, Set<String> salvo) {
        return checkShot(x + 1, y, opponent, salvo);
    }

    private String getBottomLeftCell(int x, int y, BoardStatus opponent, Set<String> salvo) {
        return checkShot(x - 1, y + 1, opponent, salvo);
    }

    private String getBottomMediumCell(int x, int y, BoardStatus opponent, Set<String> salvo) {
        return checkShot(x, y + 1, opponent, salvo);
    }

    private String getBottomRightCell(int x, int y, BoardStatus opponent, Set<String> salvo) {
        return checkShot(x + 1, y + 1, opponent, salvo);
    }

    private String checkShot(int tx, int ty, BoardStatus opponent, Set<String> salvo) {
        if (tx < 0 || tx >= Board.SIZE || ty < 0 || ty >= Board.SIZE) {
            return null;
        }
        String cellValue = opponent.getBoard().getValue(tx, ty);
        return Optional.of(getShotId(tx, ty))
                .filter(shotId -> cellValue.equalsIgnoreCase("."))
                .filter(shotId -> !salvo.contains(shotId))
                .orElse(null);
    }

    private String fireRandomly(final BoardStatus opponent, Set<String> salvo) {
        int count = 0;
        while (count++ < 30) {
            int x = randomProvider.generateUp16();
            int y = randomProvider.generateUp16();

            String cellValue = opponent.getBoard().getValue(x, y);
            String shotId = getShotId(x, y);

            if (cellValue.equalsIgnoreCase(".") && !salvo.contains(shotId)) {
                return shotId;
            }
        }
        return fireFirstNonEmptyCell(opponent, salvo);
    }

    private String fireFirstNonEmptyCell(final BoardStatus opponent, Set<String> salvo) {
        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                String cellValue = opponent.getBoard().getValue(x, y);
                String shotId = getShotId(x, y);

                if (cellValue.equalsIgnoreCase(".") && !salvo.contains(shotId)) {
                    return shotId;
                }
            }
        }
        return null;
    }

    private String getShotId(int x, int y) {
        return String.format("%sx%s", Integer.toString(x, 16), Integer.toString(y, 16));
    }


}
