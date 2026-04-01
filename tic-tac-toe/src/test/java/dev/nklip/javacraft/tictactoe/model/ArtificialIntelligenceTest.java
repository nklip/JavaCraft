package dev.nklip.javacraft.tictactoe.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.util.Random;

import static org.mockito.Mockito.when;

/**
 * User: Lipatov Nikita
 */
public class ArtificialIntelligenceTest {
    private final Player gamer = new Player("Player 1", false);
    private final Player computer = new Player("Computer", true);

    private GameField createTestGameField(int c1, int c2, int c3, int c4) {
        GameField gameField = new GameField();
        gameField.setPlayer(gamer, c1);
        gameField.setPlayer(gamer, c2);
        gameField.setPlayer(computer, c3);
        gameField.setPlayer(computer, c4);

        return gameField;
    }

    private GameField createTestGameField(int c1, int c2, int c3) {
        GameField gameField = new GameField();
        gameField.setPlayer(gamer, c1);
        gameField.setPlayer(gamer, c2);
        gameField.setPlayer(computer, c3);

        return gameField;
    }

    @Test
    public void testArtificialIntelligenceFirstMove() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, new GameField());
        ai.computerBrain();
        Assertions.assertNotSame(0, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceFirstMoveNotInTheCentre() {
        GameField gameField = new GameField();
        gameField.setPlayer(gamer, 5);
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, gameField);
        ai.computerBrain();
        Assertions.assertNotSame(0, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceHorizontalTestCase01() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, createTestGameField(1, 2, 4, 5));
        ai.makeMove(this.computer);
        Assertions.assertEquals(6, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceHorizontalTestCase02() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, createTestGameField(4, 5, 8, 9));
        ai.makeMove(this.computer);
        Assertions.assertEquals(7, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceVerticalTestCase01() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, createTestGameField(1, 4, 2, 5));
        ai.makeMove(this.computer);
        Assertions.assertEquals(8, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceVerticalTestCase02() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, createTestGameField(4, 7, 3, 6));
        ai.makeMove(this.computer);
        Assertions.assertEquals(9, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceDiagonalAttackTestCase01() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, createTestGameField(3, 7, 1, 9));
        ai.makeMove(this.computer);
        Assertions.assertEquals(5, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceDiagonalAttackTestCase02() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, createTestGameField(2, 8, 3, 5));
        ai.makeMove(this.computer);
        Assertions.assertEquals(7, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceDiagonalDefenseTestCase01() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, createTestGameField(1, 5, 8));
        ai.computerBrain();
        Assertions.assertEquals(9, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceDiagonalDefenseTestCase02() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, createTestGameField(3, 7, 2));
        ai.computerBrain();
        Assertions.assertEquals(5, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceComputerBrainWinningMoveHasPriority() {
        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, createTestGameField(1, 2, 4, 5));
        ai.computerBrain();
        Assertions.assertEquals(6, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceNoFreeCells() {
        GameField gameField = new GameField();
        for (int i = 1; i <= 9; i++) {
            gameField.setPlayer(gamer, i);
        }

        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, gameField);
        ai.computerBrain();

        Assertions.assertEquals(0, ai.getNextMove());
    }

    @Test
    public void testArtificialIntelligenceRandomRetry() {
        GameField gameField = new GameField();
        gameField.setPlayer(gamer, 2);
        gameField.setPlayer(computer, 3);
        gameField.setPlayer(computer, 4);
        gameField.setPlayer(gamer, 5);
        gameField.setPlayer(gamer, 6);
        gameField.setPlayer(gamer, 7);
        gameField.setPlayer(computer, 8);
        gameField.setPlayer(computer, 9);

        try (MockedConstruction<Random> ignored = Mockito.mockConstruction(
                Random.class,
                (mock, context) -> when(mock.nextInt(9)).thenReturn(3, 8, 0)
        )) {
            ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, gameField);
            ai.computerBrain();
            Assertions.assertEquals(1, ai.getNextMove());
        }
    }

    @Test
    public void testArtificialIntelligenceMakesOnlyOneMovePerTurn() {
        GameField gameField = new GameField();
        gameField.setPlayer(computer, 1);
        gameField.setPlayer(computer, 2);
        gameField.setPlayer(computer, 4);

        ArtificialIntelligence ai = new ArtificialIntelligence(gamer, computer, gameField);
        ai.computerBrain();

        int computerCells = 0;
        for (int cell = 1; cell <= 9; cell++) {
            if (gameField.getPlayer(cell) == computer) {
                computerCells++;
            }
        }

        Assertions.assertEquals(4, computerCells);
        boolean thirdCellTaken = gameField.getPlayer(3) == computer;
        boolean seventhCellTaken = gameField.getPlayer(7) == computer;
        Assertions.assertTrue(thirdCellTaken ^ seventhCellTaken);
    }



}
