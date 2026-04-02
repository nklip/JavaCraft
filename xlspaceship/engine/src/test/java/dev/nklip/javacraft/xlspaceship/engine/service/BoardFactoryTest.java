package dev.nklip.javacraft.xlspaceship.engine.service;

import dev.nklip.javacraft.xlspaceship.engine.game.Board;
import dev.nklip.javacraft.xlspaceship.engine.game.ships.Spaceship;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BoardFactoryTest {

    private static final int CHECK_RANDOM_COMBINATIONS = 5000;
    private static final int ALL_SHIPS_HEALTH = 41;

    private BoardFactory boardFactory;

    @BeforeEach
    public void before() {
        boardFactory = new BoardFactory(new RandomProvider());
    }

    @Test
    public void testValidateHealthValuesOnShips() {
        for (int i = 0; i < CHECK_RANDOM_COMBINATIONS; i++) {
            Board board = boardFactory.newRandomBoard();

            Assertions.assertNotNull(board.toString());
            Assertions.assertEquals(5, board.getSpaceships().size());

            int health = 0;
            for (Spaceship spaceship : board.getSpaceships()) {
                health += spaceship.getHealth();
            }
            Assertions.assertEquals(ALL_SHIPS_HEALTH, health);
        }
    }

    @Test
    public void testValidateHealthValuesOnBoards() {
        for (int i = 0; i < CHECK_RANDOM_COMBINATIONS; i++) {
            Board board = boardFactory.newRandomBoard();
            String boardLines = board.toString();
            int health = 0;
            for (int j = 0; j < boardLines.length(); j++) {
                if (Character.toString(boardLines.charAt(j)).equals("*")) {
                    health++;
                }
            }
            Assertions.assertEquals(
                    ALL_SHIPS_HEALTH,
                    health,
                    "Wrong count %s for iteration %s".formatted(health, i)
            );
        }
    }
}
