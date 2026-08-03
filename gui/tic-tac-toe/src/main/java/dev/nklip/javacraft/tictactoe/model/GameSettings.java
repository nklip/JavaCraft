package dev.nklip.javacraft.tictactoe.model;

/**
 * Game storage.
 *
 * @author Lipatov Nikita
 **/
public class GameSettings {
    private static GameSettings instance = null;

    private boolean isOddGame;
    private boolean isComputer;
    private boolean isFirstGamerMove;

    private GameSettings() {
        isOddGame = false; // value will change to true value
        isComputer = true;
        isFirstGamerMove = true;
    }

    public static GameSettings getInstance() {
        if (instance == null) {
            instance = new GameSettings();
        }
        return instance;
    }

    public boolean isOddGame() {
        return isOddGame;
    }

    public void setOddGame(boolean oddGame) {
        isOddGame = oddGame;
    }

    public boolean isComputer() {
        return isComputer;
    }

    public void setComputer(boolean computer) {
        isComputer = computer;
    }

    public boolean isFirstGamerMove() {
        return isFirstGamerMove;
    }

    public void setFirstGamerMove(boolean firstGamerMove) {
        isFirstGamerMove = firstGamerMove;
    }

    public void changeGamerMove() {
        isFirstGamerMove = !isFirstGamerMove;
    }


}
