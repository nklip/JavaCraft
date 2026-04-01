package dev.nklip.javacraft.xlspaceship.impl.service;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.xlspaceship.impl.game.Grid;
import dev.nklip.javacraft.xlspaceship.impl.game.GridStatus;
import dev.nklip.javacraft.xlspaceship.impl.model.FireRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIServices {

    private static final ExecutorService executors = Executors.newFixedThreadPool(10);

    private final UserServices userServices;
    private final RandomServices randomServices;
    private final RestServices restServices;

    public void asyncFireRequest(final String playerTurn, final String gameId, final int aliveShips, final GridStatus opponent) {
        if (userServices.isAI() && userServices.getUserId().equalsIgnoreCase(playerTurn) && aliveShips > 0) {
            executors.execute(() -> {
//                try {
                    //Thread.sleep(500L);

                    FireRequest fireRequest = new FireRequest();
                    fireRequest.setSalvo(addSalvo(opponent, aliveShips));

                    String localHost = restServices.getCurrentHostname();
                    int localPort = restServices.getCurrentPort();

                    log.debug("host = {} port = {} gameId = {} aliveShips = {} request = {}", localHost, localPort, gameId, aliveShips, fireRequest);

                    restServices.fireShotByAi(localHost, localPort, gameId, fireRequest);
//                } catch (InterruptedException ie) {
//                    log.error(ie.getMessage());
//                }
            });
        }
    }

    private List<String> addSalvo(final GridStatus opponent, int aliveShips) {
        Set<String> salvo = new LinkedHashSet<>();
        for (int i = 1; i <= aliveShips; i++) {
            salvo.add(shoot(opponent, salvo));
        }
        return new ArrayList<>(salvo);
    }

    // generate
    private String shoot(final GridStatus opponent, Set<String> salvo) {
        for (int x = 0; x < Grid.SIZE; x++) {
            for (int y = 0; y < Grid.SIZE; y++) {
                String cellValue = opponent.getGrid().getValue(x, y);

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
        return fireRandomly(opponent);
    }

    private String getTopLeftCell(int x, int y, GridStatus opponent, Set<String> salvo) {
        int tx = (x > 0) ? x - 1 : 0;
        int ty = (y > 0) ? y - 1 : 0;

        return checkShot(tx, ty, opponent, salvo);
    }

    private String getTopMediumCell(int x, int y, GridStatus opponent, Set<String> salvo) {
        int ty = (y > 0) ? y - 1 : 0;

        return checkShot(x, ty, opponent, salvo);
    }

    private String getTopRightCell(int x, int y, GridStatus opponent, Set<String> salvo) {
        int tx = (x < Grid.SIZE - 1) ? x + 1 : 0;
        int ty = (y > 0) ? y - 1 : 0;

        return checkShot(tx, ty, opponent, salvo);
    }

    private String getLeftCell(int x, int y, GridStatus opponent, Set<String> salvo) {
        int tx = (x > 0) ? x - 1 : 0;

        return checkShot(tx, y, opponent, salvo);
    }

    private String getRightCell(int x, int y, GridStatus opponent, Set<String> salvo) {
        int tx = (x < Grid.SIZE - 1) ? x + 1 : 0;

        return checkShot(tx, y, opponent, salvo);
    }

    private String getBottomLeftCell(int x, int y, GridStatus opponent, Set<String> salvo) {
        int tx = (x > 0) ? x - 1 : 0;
        int ty = (y < Grid.SIZE - 1) ? y + 1 : 0;

        return checkShot(tx, ty, opponent, salvo);
    }

    private String getBottomMediumCell(int x, int y, GridStatus opponent, Set<String> salvo) {
        int ty = (y < Grid.SIZE - 1) ? y + 1 : 0;

        return checkShot(x, ty, opponent, salvo);
    }

    private String getBottomRightCell(int x, int y, GridStatus opponent, Set<String> salvo) {
        int tx = (x < Grid.SIZE - 1) ? x + 1 : 0;
        int ty = (y < Grid.SIZE - 1) ? y + 1 : 0;

        return checkShot(tx, ty, opponent, salvo);
    }

    private String checkShot(int tx, int ty, GridStatus opponent, Set<String> salvo) {
        String cellValue = opponent.getGrid().getValue(tx, ty);
        return Optional.of(getShotId(tx, ty))
                .filter(shotId -> cellValue.equalsIgnoreCase("."))
                .filter(shotId -> !salvo.contains(shotId))
                .orElse(null);
    }

    private String fireRandomly(final GridStatus opponent) {
        int count = 0;
        while (count++ < 30) {
            int x = randomServices.generateUp16();
            int y = randomServices.generateUp16();

            String cellValue = opponent.getGrid().getValue(x, y);

            if (cellValue.equalsIgnoreCase(".")) {
                return getShotId(x, y);
            }
        }
        return fireFirstNonEmptyCell(opponent);
    }

    private String fireFirstNonEmptyCell(final GridStatus opponent) {
        for (int x = 0; x < Grid.SIZE; x++) {
            for (int y = 0; y < Grid.SIZE; y++) {
                String cellValue = opponent.getGrid().getValue(x, y);

                if (cellValue.equalsIgnoreCase(".")) {
                    return getShotId(x, y);
                }
            }
        }
        return "0x0";
    }

    private String getShotId(int x, int y) {
        return String.format("%sx%s", Integer.toString(x, 16), Integer.toString(y, 16));
    }


}
