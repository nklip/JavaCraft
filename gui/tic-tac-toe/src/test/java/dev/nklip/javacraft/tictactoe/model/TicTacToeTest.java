package dev.nklip.javacraft.tictactoe.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * User: Lipatov Nikita
 */
public class TicTacToeTest {

    @BeforeEach
    public void setUp() {
        resetGameSettings();
    }

    @AfterEach
    public void tearDown() {
        resetGameSettings();
    }

    @Test
    public void testTicTacToe_makeHumanMoveForFirstPlayer() {
        Player playerOne = new Player("Player 1", false);
        Player playerTwo = new Player("Computer", true);
        TicTacToe ticTacToe = new TicTacToe(playerOne, playerTwo);

        ticTacToe.makeHumanMove(1);
        ticTacToe.makeHumanMove(2);
        ticTacToe.makeHumanMove(3);

        Assertions.assertSame(playerOne, ticTacToe.getWinner());
    }

    @Test
    public void testTicTacToe_initialState() {
        Player playerOne = new Player("Player 1", false);
        Player playerTwo = new Player("Computer", true);
        TicTacToe ticTacToe = new TicTacToe(playerOne, playerTwo);

        Assertions.assertNull(ticTacToe.getWinner());
        Assertions.assertEquals(0, ticTacToe.getComputerMove());
    }

    @Test
    public void testTicTacToe_makeHumanMoveForSecondPlayerWhenConfigured() {
        GameSettings.getInstance().setFirstGamerMove(false);

        Player playerOne = new Player("Player 1", false);
        Player playerTwo = new Player("Player 2", false);
        TicTacToe ticTacToe = new TicTacToe(playerOne, playerTwo);

        ticTacToe.makeHumanMove(4);
        ticTacToe.makeHumanMove(5);
        ticTacToe.makeHumanMove(6);

        Assertions.assertSame(playerTwo, ticTacToe.getWinner());
    }

    @Test
    public void testTicTacToe_makeHumanMoveForFirstPlayerWhenSecondIsComputer() {
        GameSettings.getInstance().setFirstGamerMove(false);

        Player playerOne = new Player("Player 1", false);
        Player playerTwo = new Player("Computer", true);
        TicTacToe ticTacToe = new TicTacToe(playerOne, playerTwo);

        ticTacToe.makeHumanMove(1);
        ticTacToe.makeHumanMove(2);
        ticTacToe.makeHumanMove(3);

        Assertions.assertSame(playerOne, ticTacToe.getWinner());
    }

    @Test
    public void testTicTacToe_makeComputerMoveWhenSecondPlayerIsComputer() {
        Player playerOne = new Player("Player 1", false);
        Player playerTwo = new Player("Computer", true);
        TicTacToe ticTacToe = new TicTacToe(playerOne, playerTwo);

        ticTacToe.makeComputerMove();

        Assertions.assertEquals(5, ticTacToe.getComputerMove());
    }

    @Test
    public void testTicTacToe_makeComputerMoveSkippedForHumanSecondPlayer() {
        Player playerOne = new Player("Player 1", false);
        Player playerTwo = new Player("Player 2", false);
        TicTacToe ticTacToe = new TicTacToe(playerOne, playerTwo);

        ticTacToe.makeComputerMove();

        Assertions.assertEquals(0, ticTacToe.getComputerMove());
    }

    @Test
    public void testTicTacToe_printState() {
        Player playerOne = new Player("Player 1", false);
        Player playerTwo = new Player("Computer", true);
        TicTacToe ticTacToe = new TicTacToe(playerOne, playerTwo);

        ticTacToe.makeHumanMove(1);

        PrintStream oldOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            ticTacToe.printState();
        } finally {
            System.setOut(oldOut);
        }

        String printed = out.toString();
        Assertions.assertTrue(printed.contains("Player 1 || "));
        Assertions.assertTrue(printed.contains("null || "));
    }

    private void resetGameSettings() {
        GameSettings settings = GameSettings.getInstance();
        settings.setOddGame(false);
        settings.setComputer(true);
        settings.setFirstGamerMove(true);
    }
}
