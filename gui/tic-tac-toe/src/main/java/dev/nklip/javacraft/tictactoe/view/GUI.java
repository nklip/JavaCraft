package dev.nklip.javacraft.tictactoe.view;

import java.awt.*;
import java.awt.event.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import dev.nklip.javacraft.tictactoe.controller.Controller;
import dev.nklip.javacraft.tictactoe.model.GameSettings;

/**
 * Main Window
 *
 * @author Lipatov Nikita
 **/
public class GUI extends JFrame implements ActionListener {

    private static GUI instance = null;
    private List<Cell> cells;
    private final Options options;
    private final ImageOptions imageOptions;
    private final GameSettings gameSettings;
    private Controller controller;
    private javax.swing.JMenuItem aboutItem;
    // </editor-fold>//GEN-END:initComponents
    private javax.swing.JMenuItem imageItem;
    private javax.swing.JMenuItem newGameItem;
    private javax.swing.JMenuItem optionsItem;
    private GUI() {
        gameSettings = GameSettings.getInstance();
        imageOptions = ImageOptions.getInstance();
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            SwingUtilities.updateComponentTreeUI(this);
        } catch (Exception e) {
            JOptionPane.showMessageDialog
                    (
                            this,
                            "Problem with UIManager",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                    );
        }
        initComponents();
        setIconImage(ImageOptions.getInstance().getDefaultIcon().getImage()); // Icon of window
        options = Options.getInstance();
        options.setVisible(false);

        setLocation(480, 240);
        newGameItem.addActionListener(this);
        optionsItem.addActionListener(this);
        imageItem.addActionListener(this);
        aboutItem.addActionListener(this);
        setVisible(true);
    }

    public static GUI getInstance() {
        if (instance == null) {
            instance = new GUI();
        }
        return instance;
    }

    public void setController(Controller controller) {
        this.controller = controller;
    }

    /**
     * Actions for buttons.
     *
     * @param e Event - Click by any button.
     **/
    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == newGameItem) {
            this.unlockAllCells();
            controller.newGame(options);
            controller.makeFirstComputerMove(); // not odd game
        } else if (e.getSource() == optionsItem) {
            options.setVisible(true);
        } else if (e.getSource() == imageItem) {
            imageOptions.setVisible(true);
        } else if (e.getSource() == aboutItem) {
            About.getInstance();
        }
    }

    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        javax.swing.JPanel mainPanel = new javax.swing.JPanel();

        cells = new ArrayList<>(9);

        for (int i = 1; i < 10; i++) {
            Cell cell = new Cell(i);
            cell.setPreferredSize(new Dimension(96, 96));
            cell.addActionListener(this::buttonActionPerformed);
            cells.add(cell);
        }

        javax.swing.JMenuBar menuBar = new javax.swing.JMenuBar();
        javax.swing.JMenu gameMenu = new javax.swing.JMenu();
        newGameItem = new javax.swing.JMenuItem();
        javax.swing.JMenu optionsMenu = new javax.swing.JMenu();
        optionsItem = new javax.swing.JMenuItem();
        imageItem = new javax.swing.JMenuItem();
        javax.swing.JMenu aboutMenu = new javax.swing.JMenu();
        aboutItem = new javax.swing.JMenuItem();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Tic-tac-toe");
        setResizable(false);

        mainPanel.setMinimumSize(new java.awt.Dimension(384, 384));
        mainPanel.setPreferredSize(new java.awt.Dimension(384, 384));

        javax.swing.GroupLayout mainPanelLayout = new javax.swing.GroupLayout(mainPanel);
        mainPanel.setLayout(mainPanelLayout);
        mainPanelLayout.setHorizontalGroup(
                mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(mainPanelLayout.createSequentialGroup()
                                .addGroup(mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                                        .addComponent(cells.get(4 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addComponent(cells.getFirst(), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                        .addGroup(mainPanelLayout.createSequentialGroup()
                                                .addComponent(cells.get(5 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(cells.get(6 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                        .addGroup(mainPanelLayout.createSequentialGroup()
                                                .addComponent(cells.get(2 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(cells.get(3 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))))
                        .addGroup(mainPanelLayout.createSequentialGroup()
                                .addComponent(cells.get(7 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(cells.get(8 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(cells.get(9 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
        );
        mainPanelLayout.setVerticalGroup(
                mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(mainPanelLayout.createSequentialGroup()
                                .addGroup(mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                        .addComponent(cells.getFirst(), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addComponent(cells.get(2 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addComponent(cells.get(3 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                                        .addComponent(cells.get(5 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addComponent(cells.get(4 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addComponent(cells.get(6 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                        .addComponent(cells.get(7 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addComponent(cells.get(8 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addComponent(cells.get(9 - 1), javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
        );

        gameMenu.setText("Game");

        newGameItem.setText("New Game");
        gameMenu.add(newGameItem);

        menuBar.add(gameMenu);

        optionsMenu.setText("Options");

        optionsItem.setText("Options");
        optionsMenu.add(optionsItem);

        imageItem.setText("Image");
        optionsMenu.add(imageItem);

        menuBar.add(optionsMenu);

        aboutMenu.setText("About");

        aboutItem.setText("About");
        aboutMenu.add(aboutItem);

        menuBar.add(aboutMenu);

        setJMenuBar(menuBar);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(mainPanel, javax.swing.GroupLayout.PREFERRED_SIZE, 300, javax.swing.GroupLayout.PREFERRED_SIZE)
        );
        layout.setVerticalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(mainPanel, javax.swing.GroupLayout.PREFERRED_SIZE, 300, javax.swing.GroupLayout.PREFERRED_SIZE)
        );

        pack();
    }

    private void buttonActionPerformed(ActionEvent evt) {
        Cell cell = (Cell) evt.getSource();
        if (!cell.isAlreadyPressed()) {
            setUpImage(cell.getType());
            controller.action(cell.getType());
        }
    }

    /**
     * Select and install picture in cell
     **/
    public void setUpImage(int button) {
        cells.get(button - 1).setAlreadyPressed(true);
        try {
            String imagePath;
            if (gameSettings.isFirstGamerMove()) {
                imagePath = imageOptions.getPictureOne();
            } else {
                imagePath = imageOptions.getPictureTwo();
            }

            URL imageUrl = getClass().getResource(imagePath);
            if (imageUrl == null) {
                cells.get(button - 1).setAlreadyPressed(false);
                JOptionPane.showMessageDialog
                        (
                                this,
                                "Game is broken",
                                "FATAL ERROR",
                                JOptionPane.ERROR_MESSAGE
                        );
                return;
            }

            ImageIcon image = new ImageIcon(imageUrl);
            cells.get(button - 1).setIcon(image);
        } catch (Exception e) {
            cells.get(button - 1).setAlreadyPressed(false);
            JOptionPane.showMessageDialog
                    (
                            this,
                            "Game is broken",
                            "FATAL ERROR",
                            JOptionPane.ERROR_MESSAGE
                    );
        }
    }

    public void showDraw() {
        JOptionPane.showMessageDialog
                (
                        this,
                        "It's draw!",
                        "Draw",
                        JOptionPane.INFORMATION_MESSAGE
                );
    }

    public void showWinner(String strWinner) {
        JOptionPane.showMessageDialog
                (
                        this,
                        "Congratulations!!! " + strWinner + " is the winner!",
                        "Winner",
                        JOptionPane.INFORMATION_MESSAGE
                );
    }

    public void lockAllCells() {
        for (Cell cell : cells) {
            cell.setAlreadyPressed(true);
        }
    }

    public void unlockAllCells() {
        for (Cell cell : cells) {
            cell.setAlreadyPressed(false);
            cell.setIcon(null);
        }
    }

    public boolean hasFreeCells() {
        boolean isFreeCellExist = false;
        for (Cell cell : cells) {
            if (!cell.isAlreadyPressed()) {
                isFreeCellExist = true;
                break;
            }
        }
        return isFreeCellExist;
    }
}
