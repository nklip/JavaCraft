package dev.nklip.javacraft.tictactoe.model;

/**
 * User: Lipatov Nikita
 */
public class Player {
    private String name;
    private boolean isComputer;

    public Player() {
    }

    public Player(String name, boolean isComputer) {
        this.name = name;
        this.isComputer = isComputer;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isComputer() {
        return isComputer;
    }

    public void setComputer(boolean computer) {
        isComputer = computer;
    }

}
