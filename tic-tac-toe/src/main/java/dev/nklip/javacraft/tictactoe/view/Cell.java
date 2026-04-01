package dev.nklip.javacraft.tictactoe.view;

import javax.swing.*;

/**
 * User: Lipatov Nikita
 */
public class Cell extends JButton {

    private final int type;
    private boolean isAlreadyPressed;

    public Cell(int type) {
        super();
        this.type = type;
        this.isAlreadyPressed = false;
    }

    public boolean isAlreadyPressed() {
        return isAlreadyPressed;
    }

    public void setAlreadyPressed(boolean alreadyPressed) {
        isAlreadyPressed = alreadyPressed;
    }

    public int getType() {
        return type;
    }

}
