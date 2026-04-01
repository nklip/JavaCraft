package dev.nklip.javacraft.mathparser.gui.view;

import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import javax.swing.JButton;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.Timer;
import dev.nklip.javacraft.mathparser.parser.Parser;
import dev.nklip.javacraft.mathparser.parser.ParserException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/*
 * User: Lipatov Nikita
 *
 * Every test should contain @Timeout annotation
 *
 * Timeout in JUnit 5 sets a maximum allowed execution time for a test (or test method/class).
 *
 * If the test exceeds that time, JUnit fails it with a timeout error.
 *
 * It's necessary in test class, as GUI can fail in MacOS system
 * (I develop & test in MacOS, so I cannot disable it)
 */
public class GUITest {

    @Test
    @Timeout(100)
    public void testCalculateButton() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires graphics environment");
        Parser mathParser = new Parser();
        GUI instance = new GUI();
        try {
            instance.setMathParser(mathParser);

            setInputText(instance, "10+9");

            instance.calculateButton();

            Assertions.assertEquals("19.0", instance.getOutputText());
        } finally {
            instance.dispose();
        }
    }

    @Test
    @Timeout(100)
    public void testClearButton() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires graphics environment");
        GUI instance = new GUI();
        try {
            setInputText(instance, "12345");

            JButton clearButton = getField(instance, "clearButton", JButton.class);

            instance.actionPerformed(event(clearButton, "Clear"));

            Assertions.assertEquals("", instance.getInputText());
        } finally {
            instance.dispose();
        }
    }

    @Test
    @Timeout(100)
    public void testBackspaceButton() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires graphics environment");
        GUI instance = new GUI();
        try {
            setInputText(instance, "123");

            JButton backspaceButton = getField(instance, "backspaceButton", JButton.class);

            instance.actionPerformed(event(backspaceButton, "Backspace"));

            Assertions.assertEquals("12", instance.getInputText());
        } finally {
            instance.dispose();
        }
    }

    @Test
    @Timeout(100)
    public void testSaveButton() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires graphics environment");
        GUI instance = new GUI();
        try {
            setInputText(instance, "2+2");

            JButton saveButton = getField(instance, "saveButton", JButton.class);

            instance.actionPerformed(event(saveButton, "Save"));

            JTextArea history = getField(instance, "jText", JTextArea.class);
            Assertions.assertEquals("2+2\n", history.getText());
        } finally {
            instance.dispose();
        }
    }

    @Test
    @Timeout(100)
    public void testUnitSelection() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires graphics environment");
        GUI instance = new GUI();
        try {
            JRadioButton gradianCheckbox = getField(instance, "gradusCheckbox", JRadioButton.class);
            JRadioButton radianCheckbox = getField(instance, "radianCheckbox", JRadioButton.class);

            instance.actionPerformed(event(gradianCheckbox, "gradian"));

            setInputText(instance, "sin(100)");
            instance.calculateButton();
            Assertions.assertEquals("1.0", instance.getOutputText());

            instance.clearButton();
            instance.actionPerformed(event(radianCheckbox, "radian"));

            setInputText(instance, "round(sin(30))");
            instance.calculateButton();
            Assertions.assertEquals("-1.0", instance.getOutputText());
        } finally {
            instance.dispose();
        }
    }

    @Test
    @Timeout(100)
    public void testMoreLessToggle() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires graphics environment");
        GUI instance = new GUI();
        try {
            JButton moreButton = getField(instance, "moreButton", JButton.class);
            int defaultHeight = instance.getHeight();

            instance.actionPerformed(event(moreButton, "More"));
            int expandedHeight = instance.getHeight();
            Assertions.assertTrue(expandedHeight > defaultHeight);

            instance.actionPerformed(event(moreButton, "More"));
            int collapsedHeight = instance.getHeight();
            Assertions.assertEquals(defaultHeight, collapsedHeight);
        } finally {
            instance.dispose();
        }
    }

    @Test
    @Timeout(100)
    public void testHelpButton() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires graphics environment");
        GUI instance = new GUI();
        try {
            JButton helpButton = getField(instance, "helpButton", JButton.class);
            scheduleDialogClose();

            Assertions.assertDoesNotThrow(() -> instance.actionPerformed(event(helpButton, "Help")));
        } finally {
            instance.dispose();
            closeAllDialogs();
        }
    }

    @Test
    @Timeout(100)
    public void testErrorDisplay() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires graphics environment");
        GUI instance = new GUI();
        try {
            setInputText(instance, "1/0");

            JButton calculateButton = getField(instance, "calculateButton", JButton.class);

            instance.actionPerformed(event(calculateButton, "Calculate"));

            Assertions.assertEquals(
                    new ParserException(ParserException.Error.DIVISION_BY_ZERO).toString(),
                    instance.getOutputText()
            );
        } finally {
            instance.dispose();
        }
    }

    private void setInputText(GUI instance, String inputText) {
        for (int i = 0; i < inputText.length(); i++) {
            ActionEvent event = new ActionEvent(
                    instance, ActionEvent.ACTION_PERFORMED, String.valueOf(inputText.charAt(i)));
            instance.actionPerformed(event);
        }
    }

    private ActionEvent event(Object source, String command) {
        return new ActionEvent(source, ActionEvent.ACTION_PERFORMED, command);
    }

    private <T> T getField(GUI instance, String fieldName, Class<T> fieldType) throws Exception {
        Field field = GUI.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return fieldType.cast(field.get(instance));
    }

    private void scheduleDialogClose() {
        Timer timer = new Timer(150, event -> closeAllDialogs());
        timer.setRepeats(false);
        timer.start();
    }

    private void closeAllDialogs() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Dialog dialog && dialog.isShowing()) {
                dialog.dispose();
            }
        }
    }
}
