package dev.nklip.javacraft.tictactoe.view;

import javax.swing.*;

/**
 * User: Lipatov Nikita
 */
public class CheckboxItem extends JCheckBox {
    private final int panel;
    private String imageName;

    public CheckboxItem(int panel) {
        super();
        this.panel = panel;
    }

    public int getPanel() {
        return panel;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

}
