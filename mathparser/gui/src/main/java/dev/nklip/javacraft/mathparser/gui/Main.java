package dev.nklip.javacraft.mathparser.gui;

import javax.swing.SwingUtilities;
import dev.nklip.javacraft.mathparser.gui.view.GUI;
import dev.nklip.javacraft.mathparser.parser.Parser;

/**
 * Entry point of MathParser.
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
        // use Swing’s Event Dispatch Thread (EDT)
        // In Swing, all UI creation and updates should run on EDT.
        SwingUtilities.invokeLater(() -> {
            Parser mathParser = new Parser();
            GUI instance = new GUI();
            instance.setMathParser(mathParser);
        });
    }
}
