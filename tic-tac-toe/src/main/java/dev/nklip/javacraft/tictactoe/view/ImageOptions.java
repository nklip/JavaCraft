package dev.nklip.javacraft.tictactoe.view;

import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.*;

/**
 * Install images
 *
 * @author Lipatov Nikita
 **/
public class ImageOptions extends JFrame implements ActionListener {
    private static ImageOptions instance = null;
    private final String defaultIconPath;  // path to default icon
    private final String source;  // path to folder with resources (into jar)
    private String imageOne;  // path to the default first image
    private String imageTwo;  // path to the default second image

    private List<CheckboxItem> panelOne;
    private List<CheckboxItem> panelTwo;
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel imagePanelOne;
    private javax.swing.JPanel imagePanelTwo;

    /**
     * Creates new form Image
     */
    private ImageOptions() {
        source = "/img/"; // + File.separator;
        defaultIconPath = source + "Icon.png";
        imageOne = source + "DefaultCrossOne.png";
        imageTwo = source + "DefaultNoughtOne.png";
        initComponents();
        setLocation(480, 240);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.gc();
                e.getWindow().setVisible(false);
                e.getWindow().dispose();
            }
        });
        disableCheckboxesPanelOne();
        disableCheckboxesPanelTwo();

        this.panelOne.get(3).setSelected(true); // Cross
        this.panelTwo.get(5).setSelected(true); // Zero

        linkButtons();
        setIconImage(getDefaultIcon().getImage());
    }

    public static ImageOptions getInstance() {
        if (instance == null) {
            instance = new ImageOptions();
        }
        return instance;
    }

    // Add listeners to checkboxes
    private void linkButtons() {
        for (CheckboxItem item : panelOne) {
            item.addActionListener(this);
        }
        for (CheckboxItem item : panelTwo) {
            item.addActionListener(this);
        }
    }

    /**
     * Actions for checkboxes.
     *
     * @param e Event - Click on checkboxes
     **/
    @Override
    public void actionPerformed(ActionEvent e) {
        CheckboxItem item = (CheckboxItem) e.getSource();
        if (item.getPanel() == 1) {
            imageOne = item.getImageName();
            disableCheckboxesPanelOne();
        } else {
            imageTwo = item.getImageName();
            disableCheckboxesPanelTwo();
        }
        item.setSelected(true);
    }

    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        imagePanelOne = new JPanel();

        this.panelOne = new ArrayList<>(14);
        for (int i = 0; i < 14; i++) {
            CheckboxItem item = new CheckboxItem(1, i); // panel - 1; items 0-13
            this.panelOne.add(item);
        }

        this.panelOne.get(0).setText("Chrome");
        this.panelOne.get(1).setText("Firefox");
        this.panelOne.get(2).setText("Opera");
        this.panelOne.get(3).setText("Cross (default)");
        this.panelOne.get(4).setText("Cross");
        this.panelOne.get(5).setText("Zero (default)");
        this.panelOne.get(6).setText("Zero");
        this.panelOne.get(7).setText("Finder");
        this.panelOne.get(8).setText("Globe");
        this.panelOne.get(9).setText("Limewire");
        this.panelOne.get(10).setText("Netscape");
        this.panelOne.get(11).setText("Radiation");
        this.panelOne.get(12).setText("Sun");
        this.panelOne.get(13).setText("Vista");

        this.panelOne.get(0).setImageName(source + "Browser-Chrome.png");
        this.panelOne.get(1).setImageName(source + "Browser-Firefox.png");
        this.panelOne.get(2).setImageName(source + "Browser-Opera.png");
        this.panelOne.get(3).setImageName(source + "DefaultCrossOne.png");
        this.panelOne.get(4).setImageName(source + "DefaultCrossTwo.png");
        this.panelOne.get(5).setImageName(source + "DefaultNoughtOne.png");
        this.panelOne.get(6).setImageName(source + "DefaultNoughtTwo.png");
        this.panelOne.get(7).setImageName(source + "Finder.png");
        this.panelOne.get(8).setImageName(source + "Globe.png");
        this.panelOne.get(9).setImageName(source + "Limewire.png");
        this.panelOne.get(10).setImageName(source + "Netscape.png");
        this.panelOne.get(11).setImageName(source + "Radiation.png");
        this.panelOne.get(12).setImageName(source + "Sun.png");
        this.panelOne.get(13).setImageName(source + "Vista.png");

        for (CheckboxItem item : panelOne) {
            imagePanelOne.add(item);
        }

        imagePanelTwo = new JPanel();

        this.panelTwo = new ArrayList<>(14);
        for (int i = 0; i < 14; i++) {
            CheckboxItem item = new CheckboxItem(2, i); // panel - 2; items 0-13
            this.panelTwo.add(item);
        }

        this.panelTwo.get(0).setText("Chrome");
        this.panelTwo.get(1).setText("Firefox");
        this.panelTwo.get(2).setText("Opera");
        this.panelTwo.get(3).setText("Cross (default)");
        this.panelTwo.get(4).setText("Cross");
        this.panelTwo.get(5).setText("Zero (default)");
        this.panelTwo.get(6).setText("Zero");
        this.panelTwo.get(7).setText("Finder");
        this.panelTwo.get(8).setText("Globe");
        this.panelTwo.get(9).setText("Limewire");
        this.panelTwo.get(10).setText("Netscape");
        this.panelTwo.get(11).setText("Radiation");
        this.panelTwo.get(12).setText("Sun");
        this.panelTwo.get(13).setText("Vista");

        this.panelTwo.get(0).setImageName(source + "Browser-Chrome.png");
        this.panelTwo.get(1).setImageName(source + "Browser-Firefox.png");
        this.panelTwo.get(2).setImageName(source + "Browser-Opera.png");
        this.panelTwo.get(3).setImageName(source + "DefaultCrossOne.png");
        this.panelTwo.get(4).setImageName(source + "DefaultCrossTwo.png");
        this.panelTwo.get(5).setImageName(source + "DefaultNoughtOne.png");
        this.panelTwo.get(6).setImageName(source + "DefaultNoughtTwo.png");
        this.panelTwo.get(7).setImageName(source + "Finder.png");
        this.panelTwo.get(8).setImageName(source + "Globe.png");
        this.panelTwo.get(9).setImageName(source + "Limewire.png");
        this.panelTwo.get(10).setImageName(source + "Netscape.png");
        this.panelTwo.get(11).setImageName(source + "Radiation.png");
        this.panelTwo.get(12).setImageName(source + "Sun.png");
        this.panelTwo.get(13).setImageName(source + "Vista.png");

        for (CheckboxItem item : panelTwo) {
            imagePanelTwo.add(item);
        }

        setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        setTitle("Image for your game");

        imagePanelOne.setBorder(javax.swing.BorderFactory.createTitledBorder("First image:"));
        imagePanelOne.setLayout(new java.awt.GridLayout(7, 2));

        imagePanelTwo.setBorder(javax.swing.BorderFactory.createTitledBorder("Second image:"));
        imagePanelTwo.setLayout(new java.awt.GridLayout(7, 2));

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(imagePanelOne, javax.swing.GroupLayout.DEFAULT_SIZE, 238, Short.MAX_VALUE)
                        .addComponent(imagePanelTwo, javax.swing.GroupLayout.DEFAULT_SIZE, 238, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(layout.createSequentialGroup()
                                .addComponent(imagePanelOne, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(imagePanelTwo, javax.swing.GroupLayout.DEFAULT_SIZE, 180, Short.MAX_VALUE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    // End of variables declaration//GEN-END:variables

    /**
     * Switch off all checkboxes on first panel
     **/
    private void disableCheckboxesPanelOne() {
        for (CheckboxItem item : panelOne) {
            item.setSelected(false);
        }
    }

    /**
     * Switch off all checkboxes on second panel
     **/
    private void disableCheckboxesPanelTwo() {
        for (CheckboxItem item : panelTwo) {
            item.setSelected(false);
        }
    }

    /**
     * Get path to first picture
     **/
    public String getPictureOne() {
        return imageOne;
    }

    /**
     * Get path to second picture
     **/
    public String getPictureTwo() {
        return imageTwo;
    }

    /**
     * Get default ImageIcon
     **/
    public ImageIcon getDefaultIcon() {
        return new ImageIcon(Objects.requireNonNull(getClass().getResource(defaultIconPath)));
    }

}
