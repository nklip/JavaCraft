package dev.nklip.javacraft.tictactoe;

import dev.nklip.javacraft.tictactoe.controller.Controller;
import dev.nklip.javacraft.tictactoe.view.GUI;

/**
 * Entry point of Tic-tac-toe game.
 *
 * @author Lipatov Nikita
 **/
public class Main {
    /**
     * Main function.
     *
     * @param args It's not used.
     **/
    public static void main(String[] args) {
        GUI gui = GUI.getInstance();

        Controller controller = new Controller();
        controller.setView(gui);
        gui.setController(controller);
    }
}
