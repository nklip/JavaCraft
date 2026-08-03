package dev.nklip.javacraft.tictactoe.view;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/**
 * Settings of the game.
 *
 * @author Lipatov Nikita
 **/
public class Options extends JFrame implements ActionListener {
    private static Options instance = null;
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JCheckBox isFirstAlwaysMovePlayerOne;
    private javax.swing.JCheckBox isFirstAlwaysMovePlayerTwo;
    private javax.swing.JCheckBox isSecondPlayerComputer;
    private javax.swing.JCheckBox isSecondPlayerHuman;
    private javax.swing.JTextField nameComputer;
    private javax.swing.JTextField namePlayerOne;
    private javax.swing.JTextField namePlayerTwo;
    private Options() {
        initComponents();
        setLocation(480, 240);
        setTitle("Options");
        //setSize(this.getWidth(), topPanel.getHeight() + mediumPanel.getHeight() + bottomPanel.getHeight());
        setSize(332, 274);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                e.getWindow().setVisible(false);
                e.getWindow().dispose();
            }
        });
        setIconImage(ImageOptions.getInstance().getDefaultIcon().getImage());
        nameComputer.setText(System.getProperty("os.name"));

        // Add listeners
        isSecondPlayerComputer.addActionListener(this);
        isSecondPlayerHuman.addActionListener(this);
        isFirstAlwaysMovePlayerOne.addActionListener(this);
        isFirstAlwaysMovePlayerTwo.addActionListener(this);

    }

    public static Options getInstance() {
        if (instance == null) {
            instance = new Options();
        }
        return instance;
    }

    /**
     * Actions for checkboxes/buttons and e.t.c
     *
     * @param e Event - Click on checkboxes/buttons and e.t.c
     **/
    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == isSecondPlayerHuman) {
            setSecondPlayerHuman();
        } else if (e.getSource() == isSecondPlayerComputer) {
            setSecondPlayerComputer();
        } else if (e.getSource() == isFirstAlwaysMovePlayerOne) {
            moveAlwaysBelongsToPlayerOne();
        } else if (e.getSource() == isFirstAlwaysMovePlayerTwo) {
            moveAlwaysBelongsToPlayerTwo();
        }
    }

//    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        javax.swing.JPanel topPanel = new javax.swing.JPanel();
        isSecondPlayerHuman = new javax.swing.JCheckBox();
        isSecondPlayerComputer = new javax.swing.JCheckBox();
        javax.swing.JPanel mediumPanel = new javax.swing.JPanel();
        javax.swing.JLabel playerOneLabel = new javax.swing.JLabel();
        javax.swing.JLabel playerTwoLabel = new javax.swing.JLabel();
        namePlayerOne = new javax.swing.JTextField();
        namePlayerTwo = new javax.swing.JTextField();
        javax.swing.JLabel computerLabel = new javax.swing.JLabel();
        nameComputer = new javax.swing.JTextField();
        javax.swing.JPanel jPanel1 = new javax.swing.JPanel();
        isFirstAlwaysMovePlayerOne = new javax.swing.JCheckBox();
        isFirstAlwaysMovePlayerTwo = new javax.swing.JCheckBox();

        setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        setResizable(false);

        topPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("The second player:"));
        topPanel.setMaximumSize(new java.awt.Dimension(308, 32767));
        topPanel.setName("The second player"); // NOI18N
        topPanel.setPreferredSize(new java.awt.Dimension(300, 73));

        isSecondPlayerHuman.setText("Human");

        isSecondPlayerComputer.setSelected(true);
        isSecondPlayerComputer.setText("Computer");

        javax.swing.GroupLayout topPanelLayout = new javax.swing.GroupLayout(topPanel);
        topPanel.setLayout(topPanelLayout);
        topPanelLayout.setHorizontalGroup(
                topPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(topPanelLayout.createSequentialGroup()
                                .addContainerGap()
                                .addComponent(isSecondPlayerHuman)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(isSecondPlayerComputer)
                                .addContainerGap(123, Short.MAX_VALUE))
        );
        topPanelLayout.setVerticalGroup(
                topPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(topPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                .addComponent(isSecondPlayerHuman)
                                .addComponent(isSecondPlayerComputer))
        );

        mediumPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Nane of player:"));

        playerOneLabel.setText("First player:");

        playerTwoLabel.setText("Second player:");

        namePlayerOne.setFont(new java.awt.Font("DejaVu Sans", Font.PLAIN, 14));
        namePlayerOne.setHorizontalAlignment(javax.swing.JTextField.CENTER);
        namePlayerOne.setText("Player 1");

        namePlayerTwo.setFont(new java.awt.Font("DejaVu Sans", Font.PLAIN, 14));
        namePlayerTwo.setHorizontalAlignment(javax.swing.JTextField.CENTER);
        namePlayerTwo.setText("Player 2");

        computerLabel.setText("Computer:");

        nameComputer.setFont(new java.awt.Font("DejaVu Sans", Font.PLAIN, 14));
        nameComputer.setHorizontalAlignment(javax.swing.JTextField.CENTER);

        javax.swing.GroupLayout mediumPanelLayout = new javax.swing.GroupLayout(mediumPanel);
        mediumPanel.setLayout(mediumPanelLayout);
        mediumPanelLayout.setHorizontalGroup(
                mediumPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(mediumPanelLayout.createSequentialGroup()
                                .addGroup(mediumPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                                        .addComponent(playerOneLabel)
                                        .addComponent(computerLabel)
                                        .addComponent(playerTwoLabel))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(mediumPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                        .addComponent(namePlayerOne, javax.swing.GroupLayout.DEFAULT_SIZE, 189, Short.MAX_VALUE)
                                        .addComponent(namePlayerTwo, javax.swing.GroupLayout.DEFAULT_SIZE, 189, Short.MAX_VALUE)
                                        .addComponent(nameComputer, javax.swing.GroupLayout.DEFAULT_SIZE, 189, Short.MAX_VALUE)))
        );
        mediumPanelLayout.setVerticalGroup(
                mediumPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, mediumPanelLayout.createSequentialGroup()
                                .addGroup(mediumPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                        .addComponent(playerOneLabel)
                                        .addComponent(namePlayerOne, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(mediumPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                        .addComponent(playerTwoLabel)
                                        .addComponent(namePlayerTwo, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(mediumPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                        .addComponent(computerLabel)
                                        .addComponent(nameComputer, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
        );

        jPanel1.setBorder(javax.swing.BorderFactory.createTitledBorder("The first move always do:"));

        isFirstAlwaysMovePlayerOne.setText("The first player always goes the first");

        isFirstAlwaysMovePlayerTwo.setText("The second player always goes the first");

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
                jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(jPanel1Layout.createSequentialGroup()
                                .addContainerGap()
                                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                        .addComponent(isFirstAlwaysMovePlayerOne)
                                        .addComponent(isFirstAlwaysMovePlayerTwo))
                                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel1Layout.setVerticalGroup(
                jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(jPanel1Layout.createSequentialGroup()
                                .addComponent(isFirstAlwaysMovePlayerOne)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(isFirstAlwaysMovePlayerTwo))
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(mediumPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(jPanel1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                .addComponent(topPanel, javax.swing.GroupLayout.DEFAULT_SIZE, 308, Short.MAX_VALUE))
        );
        layout.setVerticalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(layout.createSequentialGroup()
                                .addContainerGap(52, Short.MAX_VALUE)
                                .addComponent(mediumPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                .addGroup(layout.createSequentialGroup()
                                        .addComponent(topPanel, javax.swing.GroupLayout.PREFERRED_SIZE, 49, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addContainerGap(212, Short.MAX_VALUE)))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents
    // End of variables declaration//GEN-END:variables

    // Second player is human
    private void setSecondPlayerHuman() {
        isSecondPlayerComputer.setSelected(false);
        isSecondPlayerHuman.setSelected(true);
    }

    // Second player is computer
    private void setSecondPlayerComputer() {
        isSecondPlayerComputer.setSelected(true);
        isSecondPlayerHuman.setSelected(false);
    }

    private void moveAlwaysBelongsToPlayerOne() {
        isFirstAlwaysMovePlayerOne.setSelected(isFirstAlwaysMovePlayerOne.isSelected());
        isFirstAlwaysMovePlayerTwo.setSelected(false);
    }

    private void moveAlwaysBelongsToPlayerTwo() {
        isFirstAlwaysMovePlayerTwo.setSelected(isFirstAlwaysMovePlayerTwo.isSelected());
        isFirstAlwaysMovePlayerOne.setSelected(false);
    }

    /**
     * Return computer or human
     * false - second player - computer
     * true  - second player - human
     **/
    public boolean isSecondPlayerComputer() {
        return !isSecondPlayerHuman.isSelected();
    }

    public boolean isFirstMoveAlwaysPlayerOne() {
        return isFirstAlwaysMovePlayerOne.isSelected();
    }

    public boolean isFirstMoveAlwaysPlayerTwo() {
        return isFirstAlwaysMovePlayerTwo.isSelected();
    }

    public String getNamePlayerOne() {
        return namePlayerOne.getText();
    }

    public String getNamePlayerTwo() {
        return namePlayerTwo.getText();
    }

    public String getNameComputer() {
        return nameComputer.getText();
    }
}
