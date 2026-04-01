package dev.nklip.javacraft.vfs.core.command;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author Lipatov Nikita
 */
public class CommandParserTest {
    @Test
    public void testClientCommandParseHostName() {
        CommandParser parser = new CommandParser();

        String connectCommand = "connect WSM3-479:4499 admin";
        parser.parse(connectCommand);
        CommandValues values = parser.getCommandValues();

        Assertions.assertEquals("connect",   values.getCommand());
        Assertions.assertEquals("WSM3-479",  values.getNextParam());
        Assertions.assertEquals("4499",      values.getNextParam());
        Assertions.assertEquals("admin",     values.getNextParam());
    }

    @Test
    public void testClientCommandParseIpAddress() {
        CommandParser parser = new CommandParser();

        String connectCommand = "connect 192.168.0.1:5999 nikita";
        parser.parse(connectCommand);
        CommandValues values = parser.getCommandValues();

        Assertions.assertEquals("connect",     values.getCommand());
        Assertions.assertEquals("192.168.0.1", values.getNextParam());
        Assertions.assertEquals("5999",        values.getNextParam());
        Assertions.assertEquals("nikita",      values.getNextParam());
    }

    @Test
    public void testClientQuitCommandParse() {
        CommandParser parser = new CommandParser();

        String quitCommand = "quit";
        parser.parse(quitCommand);
        CommandValues values = parser.getCommandValues();

        Assertions.assertEquals("quit", values.getCommand());
    }

    @Test
    public void testClientExitCommandParse() {
        CommandParser parser = new CommandParser();

        String exitCommand = "exit";
        parser.parse(exitCommand);
        CommandValues values = parser.getCommandValues();

        Assertions.assertEquals("exit", values.getCommand());
    }

    @Test
    public void testClientEmptyCommandParse() {
        CommandParser parser = new CommandParser();

        String emptyCommand = "";
        parser.parse(emptyCommand);
        CommandValues values = parser.getCommandValues();

        Assertions.assertNull(values.getCommand());
    }

    @Test
    public void testKeys() {
        CommandParser parser = new CommandParser();

        String command = "lock --r applications";

        parser.parse(command);
        CommandValues values = parser.getCommandValues();

        Assertions.assertEquals("lock", values.getCommand());
        Assertions.assertEquals("r", values.getNextKey());
        Assertions.assertEquals("applications", values.getNextParam());
    }

    @Test
    public void testCommandStartsWithDashThrowsException() {
        CommandParser parser = new CommandParser();

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> parser.parse("-wrong"));

        Assertions.assertEquals("Command error : -wrong", exception.getMessage());
    }

    @Test
    public void testDashOnlyTokenIsIgnoredAsKey() {
        CommandParser parser = new CommandParser();

        parser.parse("lock -- applications");
        CommandValues values = parser.getCommandValues();

        Assertions.assertEquals("lock", values.getCommand());
        Assertions.assertNull(values.getNextKey());
        Assertions.assertEquals("applications", values.getNextParam());
    }

    @Test
    public void testMultipleKeysAreParsedInOrder() {
        CommandParser parser = new CommandParser();

        parser.parse("copy --f --v source target");
        CommandValues values = parser.getCommandValues();

        Assertions.assertEquals("copy", values.getCommand());
        Assertions.assertEquals("f", values.getNextKey());
        Assertions.assertEquals("v", values.getNextKey());
        Assertions.assertEquals("source", values.getNextParam());
        Assertions.assertEquals("target", values.getNextParam());
    }

    @Test
    public void testParsePreservesSourceAndTrimsForCommandExtraction() {
        CommandParser parser = new CommandParser();
        String commandWithSpaces = "   stats   ";

        parser.parse(commandWithSpaces);
        CommandValues values = parser.getCommandValues();

        Assertions.assertEquals(commandWithSpaces, values.getSource());
        Assertions.assertEquals("stats", values.getCommand());
    }
}
