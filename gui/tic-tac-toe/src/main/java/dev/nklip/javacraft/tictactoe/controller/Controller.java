package dev.nklip.javacraft.tictactoe.controller;

import dev.nklip.javacraft.tictactoe.model.GameSettings;
import dev.nklip.javacraft.tictactoe.model.Player;
import dev.nklip.javacraft.tictactoe.model.TicTacToe;
import dev.nklip.javacraft.tictactoe.view.GUI;
import dev.nklip.javacraft.tictactoe.view.Options;

/**
 * User: Lipatov Nikita
 */
public class Controller {

    private GUI view;
    private TicTacToe model;
    private final GameSettings settings;

    public Controller() {
        settings = GameSettings.getInstance();

        newGame(Options.getInstance());
    }

    public void setView(GUI view) {
        this.view = view;
    }

    public TicTacToe getModel() {
        return model;
    }

    public void setModel(TicTacToe model) {
        this.model = model;
    }

    public void newGame(Options options) {
        if (options.isFirstMoveAlwaysPlayerOne()) {
            settings.setOddGame(true);
            settings.setFirstGamerMove(true);
        } else if (options.isFirstMoveAlwaysPlayerTwo()) {
            settings.setOddGame(false);
            settings.setFirstGamerMove(false);
        } else {
            boolean changeValue = !settings.isOddGame();
            settings.setOddGame(changeValue);
            settings.setFirstGamerMove(changeValue);
        }

        Player playerOne = new Player();
        playerOne.setComputer(false);
        playerOne.setName(options.getNamePlayerOne());
        Player playerTwo = new Player();
        playerTwo.setComputer(options.isSecondPlayerComputer());
        settings.setComputer(options.isSecondPlayerComputer());

        if (options.isSecondPlayerComputer()) {
            playerTwo.setName(options.getNameComputer());
        } else {
            playerTwo.setName(options.getNamePlayerTwo());
        }

        this.model = new TicTacToe(playerOne, playerTwo);
    }

    public void action(int type) {
        if (isGameOver()) {
            return;
        }

        model.makeHumanMove(type);

        if (isGameOver()) {
            return;
        }

        settings.changeGamerMove();

        if (settings.isComputer() && view.hasFreeCells()) {
            model.makeComputerMove();
            int computerMove = model.getComputerMove();
            view.setUpImage(computerMove);
            settings.changeGamerMove();
        }

        isGameOver();
    }

    boolean isGameOver() {
        Player winner = model.getWinner();
        if (winner != null) {
            view.lockAllCells();
            view.showWinner(winner.getName());
            return true;
        }

        if (!view.hasFreeCells()) {
            view.lockAllCells();
            view.showDraw();
            return true;
        }
        return false;
    }

    public void makeFirstComputerMove() {
        if (!settings.isOddGame()) {
            if (settings.isComputer() && view.hasFreeCells()) {
                model.makeComputerMove();
                int computerMove = model.getComputerMove();
                view.setUpImage(computerMove);
                settings.changeGamerMove();
            }
        }
    }


}
