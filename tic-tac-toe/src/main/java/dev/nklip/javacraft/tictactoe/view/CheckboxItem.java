package dev.nklip.javacraft.tictactoe.view;

import javax.swing.*;

/**
 * User: Lipatov Nikita
 */
public class CheckboxItem extends JCheckBox {
    private int panel;
    private int type;
    private String imageName;

    public CheckboxItem(int panel, int type) {
        super();
        this.panel = panel;
        this.type = type;
    }

    public int getPanel() {
        return panel;
    }

    public void setPanel(int panel) {
        this.panel = panel;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

}
