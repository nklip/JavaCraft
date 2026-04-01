package dev.nklip.javacraft.mathparser.gui.view;

import java.awt.*;
import java.awt.event.*;
import java.util.Objects;
import javax.swing.*;
import dev.nklip.javacraft.mathparser.gui.resources.HelpMessageLoader;
import dev.nklip.javacraft.mathparser.parser.Parser;
import dev.nklip.javacraft.mathparser.parser.ParserType;

/**
 * GUI
 *
 * @author Lipatov Nikita
 */
public class GUI extends JFrame implements ActionListener {
    private boolean trigger = false;
    private Parser mathParser = new Parser();
    private final int currentWidth;
    private final int currentHeight;
    private final int secretHeight;
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private JPanel BasePanel;
    private JPanel TopPanel;
    private JButton backspaceButton;
    private JButton calculateButton;
    private JButton clearButton;
    private JButton closeBracket;
    private JRadioButton degreeCheckbox;
    private JButton divideButton;
    private JButton eightButton;
    private JLabel firstLabel;
    private JButton fiveButton;
    private JButton fourButton;
    private JRadioButton gradusCheckbox;
    private JButton helpButton;
    private JButton indexButton;
    private JTextField inputText;
    private JTextArea jText;
    private JButton minusButton;
    private JButton modalButton;
    private JButton moreButton;
    private JButton multiButton;
    private JButton nineButton;
    private JButton oneButton;
    private JButton openBracket;
    private JTextField outputText;
    private JButton plusButton;
    private JButton pointButton;
    private JRadioButton radianCheckbox;
    private JButton saveButton;
    private JLabel secondLabel;
    private JButton sevenButton;
    private JButton sixButton;
    private JScrollPane text;
    private JButton threeButton;
    private JButton twoButton;
    private JButton zeroButton;

    public GUI() {
        initComponents();
        setIconImage(getImageIcon()); // Icon of window

        currentWidth = this.getWidth();
        currentHeight = this.TopPanel.getHeight();
        secretHeight = this.BasePanel.getHeight();
        setTitle("MathParser");
        pack();
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setVisible(true);
        setLocation(320, 240);
        setSize(currentWidth, currentHeight + 35);

        calculateButton.addActionListener(this);
        moreButton.addActionListener(this);
        saveButton.addActionListener(this);
        helpButton.addActionListener(this);
        backspaceButton.addActionListener(this);
        zeroButton.addActionListener(this);
        oneButton.addActionListener(this);
        twoButton.addActionListener(this);
        threeButton.addActionListener(this);
        fourButton.addActionListener(this);
        fiveButton.addActionListener(this);
        sixButton.addActionListener(this);
        sevenButton.addActionListener(this);
        eightButton.addActionListener(this);
        nineButton.addActionListener(this);
        plusButton.addActionListener(this);
        minusButton.addActionListener(this);
        divideButton.addActionListener(this);
        multiButton.addActionListener(this);
        indexButton.addActionListener(this);
        modalButton.addActionListener(this);
        pointButton.addActionListener(this);
        openBracket.addActionListener(this);
        closeBracket.addActionListener(this);
        degreeCheckbox.addActionListener(this);
        radianCheckbox.addActionListener(this);
        gradusCheckbox.addActionListener(this);
        clearButton.addActionListener(this);

        // Handling actions for Enter button
        inputText.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    calculateButton();
                }
            }
        });
    }

    public void setMathParser(Parser mathParser) {
        this.mathParser = Objects.requireNonNull(mathParser, "mathParser cannot be null");
    }

    private Image getImageIcon() {
        ImageIcon icon = new ImageIcon(Objects.requireNonNull(
                getClass().getResource("/img/calculator.png")
        ));
        return icon.getImage();
    }

    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        TopPanel = new JPanel();
        firstLabel = new JLabel();
        secondLabel = new JLabel();
        inputText = new JTextField();
        outputText = new JTextField();
        calculateButton = new JButton();
        moreButton = new JButton();
        BasePanel = new JPanel();
        sevenButton = new JButton();
        eightButton = new JButton();
        nineButton = new JButton();
        divideButton = new JButton();
        multiButton = new JButton();
        sixButton = new JButton();
        fiveButton = new JButton();
        fourButton = new JButton();
        minusButton = new JButton();
        threeButton = new JButton();
        twoButton = new JButton();
        oneButton = new JButton();
        plusButton = new JButton();
        pointButton = new JButton();
        zeroButton = new JButton();
        helpButton = new JButton();
        saveButton = new JButton();
        backspaceButton = new JButton();
        indexButton = new JButton();
        modalButton = new JButton();
        clearButton = new JButton();
        degreeCheckbox = new JRadioButton();
        gradusCheckbox = new JRadioButton();
        radianCheckbox = new JRadioButton();
        text = new JScrollPane();
        jText = new JTextArea();
        openBracket = new JButton();
        closeBracket = new JButton();

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        firstLabel.setText("Input :");

        secondLabel.setText("Output:");

        calculateButton.setText("Calculate");

        moreButton.setText("More");

        GroupLayout TopPanelLayout = new GroupLayout(TopPanel);
        TopPanel.setLayout(TopPanelLayout);
        TopPanelLayout.setHorizontalGroup(
                TopPanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                        .addGroup(TopPanelLayout.createSequentialGroup()
                                .addContainerGap()
                                .addGroup(TopPanelLayout.createParallelGroup(GroupLayout.Alignment.TRAILING)
                                        .addComponent(secondLabel)
                                        .addComponent(firstLabel))
                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(TopPanelLayout.createParallelGroup(GroupLayout.Alignment.TRAILING)
                                        .addComponent(inputText, GroupLayout.Alignment.LEADING, GroupLayout.DEFAULT_SIZE, 474, Short.MAX_VALUE)
                                        .addComponent(outputText, GroupLayout.Alignment.LEADING, GroupLayout.DEFAULT_SIZE, 474, Short.MAX_VALUE))
                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(TopPanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING, false)
                                        .addComponent(moreButton, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                        .addComponent(calculateButton)))
        );
        TopPanelLayout.setVerticalGroup(
                TopPanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                        .addGroup(TopPanelLayout.createSequentialGroup()
                                .addContainerGap(GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addGroup(TopPanelLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                                        .addComponent(calculateButton)
                                        .addComponent(inputText, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                        .addComponent(firstLabel))
                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(TopPanelLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                                        .addComponent(moreButton)
                                        .addComponent(outputText, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                        .addComponent(secondLabel)))
        );

        sevenButton.setText("7");
        sevenButton.setPreferredSize(new Dimension(39, 30));

        eightButton.setText("8");
        eightButton.setPreferredSize(new Dimension(39, 30));

        nineButton.setText("9");
        nineButton.setPreferredSize(new Dimension(39, 30));

        divideButton.setText("/");
        divideButton.setPreferredSize(new Dimension(41, 30));

        multiButton.setText("*");
        multiButton.setPreferredSize(new Dimension(41, 30));

        sixButton.setText("6");
        sixButton.setPreferredSize(new Dimension(39, 30));

        fiveButton.setText("5");
        fiveButton.setPreferredSize(new Dimension(39, 30));

        fourButton.setText("4");
        fourButton.setPreferredSize(new Dimension(39, 30));

        minusButton.setText("-");
        minusButton.setPreferredSize(new Dimension(41, 30));

        threeButton.setText("3");
        threeButton.setPreferredSize(new Dimension(39, 30));

        twoButton.setText("2");
        twoButton.setPreferredSize(new Dimension(39, 30));

        oneButton.setText("1");
        oneButton.setPreferredSize(new Dimension(39, 30));

        plusButton.setText("+");
        plusButton.setPreferredSize(new Dimension(41, 30));

        pointButton.setText(".");
        pointButton.setPreferredSize(new Dimension(39, 30));

        zeroButton.setText("0");
        zeroButton.setPreferredSize(new Dimension(39, 30));

        helpButton.setText("Help");
        helpButton.setPreferredSize(new Dimension(41, 30));

        saveButton.setText("Save");
        saveButton.setPreferredSize(new Dimension(39, 30));

        backspaceButton.setText("Backspace");
        backspaceButton.setPreferredSize(new Dimension(41, 30));

        indexButton.setText("^");
        indexButton.setPreferredSize(new Dimension(39, 30));

        modalButton.setText("%");
        modalButton.setPreferredSize(new Dimension(39, 30));

        clearButton.setText("Clear");

        degreeCheckbox.setSelected(true);
        degreeCheckbox.setText("degree");

        gradusCheckbox.setText("gradian");

        radianCheckbox.setText("radian");

        jText.setColumns(20);
        jText.setRows(5);
        text.setViewportView(jText);

        openBracket.setText("(");
        openBracket.setPreferredSize(new Dimension(39, 30));

        closeBracket.setText(")");
        closeBracket.setPreferredSize(new Dimension(39, 30));

        GroupLayout BasePanelLayout = new GroupLayout(BasePanel);
        BasePanel.setLayout(BasePanelLayout);
        BasePanelLayout.setHorizontalGroup(
                BasePanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                        .addGroup(BasePanelLayout.createSequentialGroup()
                                .addContainerGap()
                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.TRAILING)
                                        .addGroup(BasePanelLayout.createSequentialGroup()
                                                .addComponent(degreeCheckbox)
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(radianCheckbox)
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
                                                .addComponent(gradusCheckbox))
                                        .addComponent(text, GroupLayout.DEFAULT_SIZE, 311, Short.MAX_VALUE))
                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                                        .addGroup(BasePanelLayout.createSequentialGroup()
                                                .addComponent(fourButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE)
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(fiveButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE)
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(sixButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE)
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(multiButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE))
                                        .addGroup(BasePanelLayout.createSequentialGroup()
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.TRAILING, false)
                                                        .addComponent(zeroButton, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                                        .addGroup(GroupLayout.Alignment.LEADING, BasePanelLayout.createSequentialGroup()
                                                                .addComponent(oneButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE)
                                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                                .addComponent(twoButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE)))
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                                                        .addGroup(BasePanelLayout.createSequentialGroup()
                                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                                .addComponent(threeButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE)
                                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                                .addComponent(minusButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE))
                                                        .addGroup(GroupLayout.Alignment.TRAILING, BasePanelLayout.createSequentialGroup()
                                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                                .addComponent(pointButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE)
                                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                                .addComponent(plusButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE))))
                                        .addGroup(BasePanelLayout.createSequentialGroup()
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING, false)
                                                        .addComponent(openBracket, GroupLayout.Alignment.TRAILING, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                                        .addComponent(sevenButton, GroupLayout.Alignment.TRAILING, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE))
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.TRAILING, false)
                                                        .addComponent(closeBracket, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                                        .addComponent(eightButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE))
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.TRAILING)
                                                        .addGroup(BasePanelLayout.createSequentialGroup()
                                                                .addComponent(nineButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE)
                                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                                .addComponent(divideButton, GroupLayout.DEFAULT_SIZE, 42, GroupLayout.DEFAULT_SIZE))
                                                        .addComponent(helpButton, GroupLayout.PREFERRED_SIZE, 90, GroupLayout.PREFERRED_SIZE))))
                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.TRAILING, false)
                                        .addComponent(clearButton, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                        .addComponent(saveButton, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                        .addComponent(indexButton, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                        .addComponent(modalButton, GroupLayout.DEFAULT_SIZE, 90, Short.MAX_VALUE)
                                        .addComponent(backspaceButton, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                                .addContainerGap())
        );
        BasePanelLayout.setVerticalGroup(
                BasePanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                        .addGroup(BasePanelLayout.createSequentialGroup()
                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                                        .addGroup(BasePanelLayout.createSequentialGroup()
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                                                        .addComponent(helpButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(openBracket, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(closeBracket, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(gradusCheckbox)
                                                        .addComponent(radianCheckbox)
                                                        .addComponent(degreeCheckbox))
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                                                        .addComponent(sevenButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(eightButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(nineButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(divideButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE))
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                                                        .addComponent(fourButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(fiveButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(sixButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(multiButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE))
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                                                        .addComponent(oneButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(twoButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(threeButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(minusButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE))
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addGroup(BasePanelLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                                                        .addComponent(zeroButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(pointButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                        .addComponent(plusButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)))
                                        .addGroup(BasePanelLayout.createSequentialGroup()
                                                .addComponent(backspaceButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(clearButton, GroupLayout.PREFERRED_SIZE, 30, GroupLayout.PREFERRED_SIZE)
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(saveButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(indexButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(modalButton, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)))
                                .addContainerGap(GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                        .addGroup(GroupLayout.Alignment.TRAILING, BasePanelLayout.createSequentialGroup()
                                .addContainerGap(32, Short.MAX_VALUE)
                                .addComponent(text, GroupLayout.PREFERRED_SIZE, 142, GroupLayout.PREFERRED_SIZE)
                                .addContainerGap())
        );

        GroupLayout layout = new GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
                layout.createParallelGroup(GroupLayout.Alignment.LEADING)
                        .addGroup(layout.createSequentialGroup()
                                .addContainerGap()
                                .addGroup(layout.createParallelGroup(GroupLayout.Alignment.LEADING)
                                        .addComponent(BasePanel, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                        .addGroup(layout.createSequentialGroup()
                                                .addComponent(TopPanel, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                                .addContainerGap())))
        );
        layout.setVerticalGroup(
                layout.createParallelGroup(GroupLayout.Alignment.LEADING)
                        .addGroup(layout.createSequentialGroup()
                                .addComponent(TopPanel, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(BasePanel, GroupLayout.PREFERRED_SIZE, 180, GroupLayout.PREFERRED_SIZE)
                                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents
    // End of variables declaration//GEN-END:variables

    /**
     * Handler of buttons
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == calculateButton) { // calculate button
            calculateButton();
        } else if (e.getSource() == moreButton) { // more-button
            moreButton();
        } else if (e.getSource() == saveButton) { // save-button
            saveButton();
        } else if (e.getSource() == helpButton) { // help-button
            helpButton();
        } else if (e.getSource() == backspaceButton) { // backspace-button
            backspaceButton();
        } else if (e.getSource() == clearButton) {
            clearButton();
        } else if (e.getSource() == degreeCheckbox ||
                e.getSource() == radianCheckbox ||
                e.getSource() == gradusCheckbox) { // checkbox case
            checkBox(e.getActionCommand());
        } else { // other buttons
            addOneCharacter(e.getActionCommand());
        }
    }

    public void calculateButton() {
        String str = inputText.getText();
        outputText.setText(mathParser.calculate(str));
    }

    public String getOutputText() {
        return outputText.getText();
    }

    public void clearButton() {
        inputText.setText("");
    }

    public String getInputText() {
        return inputText.getText();
    }

    private void moreButton() {
        if (trigger) {
            setSize(currentWidth, currentHeight + 35);
            trigger = false;
        } else {
            setSize(currentWidth, currentHeight + secretHeight + 40);
            trigger = true;
        }
    }

    private void saveButton() {
        jText.setText(jText.getText() + inputText.getText() + "\n");
    }

    private void helpButton() {
        String message = HelpMessageLoader.loadHelpMessage();

        // we use JTextArea && JScrollPane to preserve text formatting above
        JTextArea area = new JTextArea(message);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setTabSize(4);
        area.setCaretPosition(0);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane pane = new JScrollPane(area);
        pane.setPreferredSize(new Dimension(570, 500));

        JOptionPane.showMessageDialog
                (
                        null,
                        pane,
                        "Help",
                        JOptionPane.INFORMATION_MESSAGE
                );
    }

    private void backspaceButton() {
        String str = inputText.getText();
        if (!str.isEmpty()) {
            inputText.setText(str.substring(0, str.length() - 1));
        }
    }

    private void checkBox(String str) {
        str = str.toLowerCase();
        degreeCheckbox.setSelected(false);
        radianCheckbox.setSelected(false);
        gradusCheckbox.setSelected(false);
        switch (str) {
            case "degree" -> {
                mathParser.setTangentUnit(ParserType.DEGREE);
                degreeCheckbox.setSelected(true);
            }
            case "gradian" -> {
                mathParser.setTangentUnit(ParserType.GRADIAN);
                gradusCheckbox.setSelected(true);
            }
            case "radian" -> {
                mathParser.setTangentUnit(ParserType.RADIAN);
                radianCheckbox.setSelected(true);
            }
        }
    }

    private void addOneCharacter(String str) {
        inputText.setText(inputText.getText() + str);
    }

}
