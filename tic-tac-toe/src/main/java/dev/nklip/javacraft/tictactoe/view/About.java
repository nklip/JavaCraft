package dev.nklip.javacraft.tictactoe.view;

import javax.swing.*;

/**
 * Information about game and environment
 *
 * @author Lipatov Nikita
 **/
public class About extends JFrame {
    private static About instance = null;
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private JEditorPane editorPane;
    private JPanel mainPanel;
    private JScrollPane scrollPane;

    private About() {
        initComponents();
        setIconImage(ImageOptions.getInstance().getDefaultIcon().getImage()); // Вставляем к себе картинку
        setLocation(480, 240);
        setVisible(true);
        editorPane.setContentType("text/html");
        editorPane.setText(getTextAboutSystem());
    }

    public static About getInstance() {
        if (instance == null) {
            instance = new About();
        }
        return instance;
    }

    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        mainPanel = new javax.swing.JPanel();
        scrollPane = new javax.swing.JScrollPane();
        editorPane = new javax.swing.JEditorPane();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("About game");
        setResizable(false);

        mainPanel.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Information about game:", javax.swing.border.TitledBorder.CENTER, javax.swing.border.TitledBorder.DEFAULT_POSITION));
        mainPanel.setMaximumSize(new java.awt.Dimension(300, 165));
        mainPanel.setMinimumSize(new java.awt.Dimension(300, 165));
        mainPanel.setPreferredSize(new java.awt.Dimension(300, 165));

        scrollPane.setViewportView(editorPane);

        javax.swing.GroupLayout mainPanelLayout = new javax.swing.GroupLayout(mainPanel);
        mainPanel.setLayout(mainPanelLayout);
        mainPanelLayout.setHorizontalGroup(
                mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(scrollPane, javax.swing.GroupLayout.DEFAULT_SIZE, 288, Short.MAX_VALUE)
        );
        mainPanelLayout.setVerticalGroup(
                mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(mainPanelLayout.createSequentialGroup()
                                .addComponent(scrollPane, javax.swing.GroupLayout.PREFERRED_SIZE, 138, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(mainPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(layout.createSequentialGroup()
                                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(mainPanel, javax.swing.GroupLayout.PREFERRED_SIZE, 167, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents
    // End of variables declaration//GEN-END:variables

    // get required information
    private String getTextAboutSystem() {
        return "<B><CENTER>This game wrote on the Java.</CENTER><BR>" +
                "Version of game:</B> 1.0.0<BR>" +
                "<B>Version of JVM: </B>" + System.getProperty("java.version") + "<BR>" +
                "<B>Your OS: </B>" + System.getProperty("os.name") + "<BR>" +
                "<B>Your vendor: </B>" + System.getProperty("java.vendor") + "<BR>" +
                "<B>Your desktop: </B>" + System.getProperty("sun.desktop") + "<BR>";
    }
}
