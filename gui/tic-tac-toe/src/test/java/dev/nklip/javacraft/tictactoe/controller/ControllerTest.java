package dev.nklip.javacraft.tictactoe.controller;

import dev.nklip.javacraft.tictactoe.model.GameSettings;
import dev.nklip.javacraft.tictactoe.model.Player;
import dev.nklip.javacraft.tictactoe.model.TicTacToe;
import dev.nklip.javacraft.tictactoe.view.GUI;
import dev.nklip.javacraft.tictactoe.view.Options;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * User: Lipatov Nikita
 */
public class ControllerTest {

    @Test
    public void testControllerSingleGame() {
        GUI gui = Mockito.mock(GUI.class);
        Mockito.when(gui.hasFreeCells()).thenReturn(true);

        Controller controller = new Controller();
        controller.setView(gui);
        gui.setController(controller);

        Options options = mockOptions();

        // default values
        GameSettings.getInstance().setOddGame(false);
        GameSettings.getInstance().setComputer(true);
        GameSettings.getInstance().setFirstGamerMove(true);

        controller.newGame(options);
        Assertions.assertTrue(GameSettings.getInstance().isFirstGamerMove());
        controller.action(1); // first player
        controller.action(5); // second player
        controller.action(9); // first player
        controller.action(3); // second player
        controller.action(7); // first player
        controller.action(8); // second player
        controller.action(4); // first player

        Mockito.verify(gui).showWinner("Player 1");
    }

    @Test
    public void testControllerTwoGames() {
        GUI gui = Mockito.mock(GUI.class);
        Mockito.when(gui.hasFreeCells()).thenReturn(true);

        Controller controller = new Controller();
        controller.setView(gui);
        gui.setController(controller);

        Options options = mockOptions();

        // default values
        GameSettings.getInstance().setOddGame(false);
        GameSettings.getInstance().setComputer(true);
        GameSettings.getInstance().setFirstGamerMove(true);

        controller.newGame(options);
        Assertions.assertTrue(GameSettings.getInstance().isFirstGamerMove());
        controller.action(4); // first player
        controller.action(1); // second player
        controller.action(2); // first player
        controller.action(5); // second player
        controller.action(6); // first player
        controller.action(9); // second player
        Mockito.verify(gui).showWinner("Player 2");

        controller.newGame(options);
        Assertions.assertFalse(GameSettings.getInstance().isFirstGamerMove());
        controller.action(5); // second player
        controller.action(7); // first player
        controller.action(1); // second player
        controller.action(9); // first player
        controller.action(8); // second player
        controller.action(3); // first player
        controller.action(4); // second player
        controller.action(6); // first player
        Mockito.verify(gui).showWinner("Player 1");
    }

    @Test
    public void testControllerNewGameFirstMoveAlwaysPlayerOne() {
        Controller controller = new Controller();
        Options options = Mockito.mock(Options.class);
        Mockito.when(options.isSecondPlayerComputer()).thenReturn(true);
        Mockito.when(options.getNamePlayerOne()).thenReturn("Player 1");
        Mockito.when(options.getNamePlayerTwo()).thenReturn("Player 2");
        Mockito.when(options.getNameComputer()).thenReturn("Computer");
        Mockito.when(options.isFirstMoveAlwaysPlayerOne()).thenReturn(true);
        Mockito.when(options.isFirstMoveAlwaysPlayerTwo()).thenReturn(false);

        controller.newGame(options);

        Assertions.assertTrue(GameSettings.getInstance().isOddGame());
        Assertions.assertTrue(GameSettings.getInstance().isFirstGamerMove());
        Assertions.assertTrue(GameSettings.getInstance().isComputer());
        Assertions.assertNotNull(controller.getModel());
    }

    @Test
    public void testControllerNewGameFirstMoveAlwaysPlayerTwo() {
        Controller controller = new Controller();
        Options options = Mockito.mock(Options.class);
        Mockito.when(options.isSecondPlayerComputer()).thenReturn(true);
        Mockito.when(options.getNamePlayerOne()).thenReturn("Player 1");
        Mockito.when(options.getNamePlayerTwo()).thenReturn("Player 2");
        Mockito.when(options.getNameComputer()).thenReturn("Computer");
        Mockito.when(options.isFirstMoveAlwaysPlayerOne()).thenReturn(false);
        Mockito.when(options.isFirstMoveAlwaysPlayerTwo()).thenReturn(true);

        controller.newGame(options);

        Assertions.assertFalse(GameSettings.getInstance().isOddGame());
        Assertions.assertFalse(GameSettings.getInstance().isFirstGamerMove());
        Assertions.assertTrue(GameSettings.getInstance().isComputer());
        Assertions.assertNotNull(controller.getModel());
    }

    @Test
    public void testControllerIsGameOverDraw() {
        GUI gui = Mockito.mock(GUI.class);
        TicTacToe model = Mockito.mock(TicTacToe.class);
        Mockito.when(model.getWinner()).thenReturn(null);
        Mockito.when(gui.hasFreeCells()).thenReturn(false);

        Controller controller = new Controller();
        controller.setView(gui);
        controller.setModel(model);

        Assertions.assertTrue(controller.isGameOver());
        Mockito.verify(gui).lockAllCells();
        Mockito.verify(gui).showDraw();
    }

    @Test
    public void testControllerIsGameOverFalse() {
        GUI gui = Mockito.mock(GUI.class);
        TicTacToe model = Mockito.mock(TicTacToe.class);
        Mockito.when(model.getWinner()).thenReturn(null);
        Mockito.when(gui.hasFreeCells()).thenReturn(true);

        Controller controller = new Controller();
        controller.setView(gui);
        controller.setModel(model);

        Assertions.assertFalse(controller.isGameOver());
        Mockito.verify(gui, Mockito.never()).showWinner(Mockito.anyString());
        Mockito.verify(gui, Mockito.never()).showDraw();
    }

    @Test
    public void testControllerActionStopsWhenGameAlreadyOver() {
        GUI gui = Mockito.mock(GUI.class);
        TicTacToe model = Mockito.mock(TicTacToe.class);
        Player winner = new Player("winner", false);
        Mockito.when(model.getWinner()).thenReturn(winner);

        Controller controller = new Controller();
        controller.setView(gui);
        controller.setModel(model);

        controller.action(1);

        Mockito.verify(model, Mockito.never()).makeHumanMove(Mockito.anyInt());
        Mockito.verify(gui).showWinner("winner");
    }

    @Test
    public void testControllerActionWithComputerMove() {
        GUI gui = Mockito.mock(GUI.class);
        TicTacToe model = Mockito.mock(TicTacToe.class);
        Mockito.when(model.getWinner()).thenReturn(null);
        Mockito.when(model.getComputerMove()).thenReturn(5);
        Mockito.when(gui.hasFreeCells()).thenReturn(true);

        GameSettings.getInstance().setComputer(true);

        Controller controller = new Controller();
        controller.setView(gui);
        controller.setModel(model);

        controller.action(1);

        Mockito.verify(model).makeHumanMove(1);
        Mockito.verify(model).makeComputerMove();
        Mockito.verify(gui).setUpImage(5);
    }

    @Test
    public void testControllerActionSkipsComputerMoveWhenNoFreeCellsForComputerTurn() {
        GUI gui = Mockito.mock(GUI.class);
        TicTacToe model = Mockito.mock(TicTacToe.class);
        Mockito.when(model.getWinner()).thenReturn(null);
        Mockito.when(gui.hasFreeCells()).thenReturn(true, true, false, true);

        GameSettings.getInstance().setComputer(true);

        Controller controller = new Controller();
        controller.setView(gui);
        controller.setModel(model);

        controller.action(1);

        Mockito.verify(model).makeHumanMove(1);
        Mockito.verify(model, Mockito.never()).makeComputerMove();
    }

    @Test
    public void testControllerMakeFirstComputerMove() {
        GUI gui = Mockito.mock(GUI.class);
        TicTacToe model = Mockito.mock(TicTacToe.class);
        Mockito.when(model.getComputerMove()).thenReturn(5);
        Mockito.when(gui.hasFreeCells()).thenReturn(true);

        Controller controller = new Controller();
        controller.setView(gui);
        controller.setModel(model);
        GameSettings.getInstance().setOddGame(false);
        GameSettings.getInstance().setComputer(true);

        controller.makeFirstComputerMove();

        Mockito.verify(model).makeComputerMove();
        Mockito.verify(gui).setUpImage(5);
    }

    @Test
    public void testControllerMakeFirstComputerMoveSkippedWhenComputerDisabled() {
        GUI gui = Mockito.mock(GUI.class);
        TicTacToe model = Mockito.mock(TicTacToe.class);
        Mockito.when(gui.hasFreeCells()).thenReturn(true);

        Controller controller = new Controller();
        controller.setView(gui);
        controller.setModel(model);
        GameSettings.getInstance().setOddGame(false);
        GameSettings.getInstance().setComputer(false);

        controller.makeFirstComputerMove();

        Mockito.verify(model, Mockito.never()).makeComputerMove();
        Mockito.verify(gui, Mockito.never()).setUpImage(Mockito.anyInt());
    }

    @Test
    public void testControllerMakeFirstComputerMoveSkippedWhenNoFreeCells() {
        GUI gui = Mockito.mock(GUI.class);
        TicTacToe model = Mockito.mock(TicTacToe.class);
        Mockito.when(gui.hasFreeCells()).thenReturn(false);

        Controller controller = new Controller();
        controller.setView(gui);
        controller.setModel(model);
        GameSettings.getInstance().setOddGame(false);
        GameSettings.getInstance().setComputer(true);

        controller.makeFirstComputerMove();

        Mockito.verify(model, Mockito.never()).makeComputerMove();
        Mockito.verify(gui, Mockito.never()).setUpImage(Mockito.anyInt());
    }

    @Test
    public void testControllerMakeFirstComputerMoveSkippedForOddGame() {
        GUI gui = Mockito.mock(GUI.class);
        TicTacToe model = Mockito.mock(TicTacToe.class);
        Mockito.when(gui.hasFreeCells()).thenReturn(true);

        Controller controller = new Controller();
        controller.setView(gui);
        controller.setModel(model);
        GameSettings.getInstance().setOddGame(true);
        GameSettings.getInstance().setComputer(true);

        controller.makeFirstComputerMove();

        Mockito.verify(model, Mockito.never()).makeComputerMove();
        Mockito.verify(gui, Mockito.never()).setUpImage(Mockito.anyInt());
    }

    @Test
    public void testControllerSetAndGetModel() {
        Controller controller = new Controller();
        TicTacToe model = Mockito.mock(TicTacToe.class);

        controller.setModel(model);

        Assertions.assertSame(model, controller.getModel());
    }

    private Options mockOptions() {
        Options options = Mockito.mock(Options.class);
        Mockito.when(options.isSecondPlayerComputer()).thenReturn(false); /* reference attr group */
        Mockito.when(options.getNamePlayerOne()).thenReturn("Player 1"); /* reference attr group */
        Mockito.when(options.getNamePlayerTwo()).thenReturn("Player 2"); /* reference attr group */
        Mockito.when(options.getNameComputer()).thenReturn("Computer"); /* reference attr group */
        Mockito.when(options.isFirstMoveAlwaysPlayerOne()).thenReturn(false); /* reference attr group */
        Mockito.when(options.isFirstMoveAlwaysPlayerTwo()).thenReturn(false); /* reference attr group */
        return options;
    }
}
