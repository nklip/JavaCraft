package dev.nklip.vfs.client;

import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import dev.nklip.vfs.client.network.MessageSender;
import dev.nklip.vfs.client.network.NetworkManager;
import dev.nklip.vfs.client.network.UserManager;
import dev.nklip.vfs.core.VFSConstants;
import dev.nklip.vfs.core.command.CommandParser;
import dev.nklip.vfs.core.exceptions.QuitException;

import dev.nklip.vfs.core.network.protocol.Protocol;

/**
 * @author Lipatov Nikita
 */
public class CommandLineTest {


    @Test
    public void testConnectCommand() throws Exception {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        when(userManager.isAuthorized()).thenReturn(false);

        CommandLine cmd = new CommandLine(userManager, networkManager);
        cmd.execute("connect localhost:4499 nikita");

        Protocol.User user = Protocol.User.newBuilder()
                .setId(VFSConstants.NEW_USER)
                .setLogin("nikita")
                .build();
        verify(networkManager, atLeastOnce()).openSocket("localhost", "4499");
        verify(userManager, atLeastOnce()).setUser(user);
        verify(messageSender, atLeastOnce()).send(user, "connect nikita");
    }

    @Test
    public void testConnectCommandAlreadyAuthorized() {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        when(userManager.isAuthorized()).thenReturn(true);

        CommandLine cmd = new CommandLine(userManager, networkManager);
        CommandParser parser = new CommandParser();
        parser.parse("connect localhost:4499 nikita");
        cmd.commandValues = parser.getCommandValues();

        RuntimeException thrown = Assertions.assertThrows(
                RuntimeException.class,
                () -> cmd.commands.get("connect").run()
        );
        Assertions.assertEquals("You are already authorized!", thrown.getMessage());
    }

    @Test
    public void testQuitCommandNotAuthorized() {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        when(userManager.isAuthorized()).thenReturn(false);

        CommandLine cmd = new CommandLine(userManager, networkManager);
        CommandParser parser = new CommandParser();
        parser.parse("quit");
        cmd.commandValues = parser.getCommandValues();

        RuntimeException thrown = Assertions.assertThrows(
                RuntimeException.class,
                () -> cmd.commands.get("quit").run()
        );
        Assertions.assertEquals("You are not authorized or connection was lost!", thrown.getMessage());
    }

    @Test
    public void testQuitCommand() {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        when(userManager.isAuthorized()).thenReturn(true);
        Protocol.User user = Protocol.User.newBuilder()
                .setId(VFSConstants.NEW_USER)
                .setLogin("nikita")
                .build();
        when(userManager.getUser()).thenReturn(user);

        CommandLine cmd = new CommandLine(userManager, networkManager);
        cmd.execute("quit");

        verify(networkManager, atLeastOnce()).getMessageSender();
        verify(userManager, atLeastOnce()).getUser();
        verify(messageSender, atLeastOnce()).send(user, "quit");
    }

    @Test
    public void testDefaultCommandNotAuthorized() {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        when(userManager.isAuthorized()).thenReturn(false);

        CommandLine cmd = new CommandLine(userManager, networkManager);
        CommandParser parser = new CommandParser();
        parser.parse("default");

        RuntimeException thrown = Assertions.assertThrows(
                RuntimeException.class,
                () -> cmd.commands.get("default").run()
        );
        Assertions.assertEquals("Please connect to the server!", thrown.getMessage());
    }

    @Test
    public void testDefaultCommand() {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        when(userManager.isAuthorized()).thenReturn(true);
        Protocol.User user = Protocol.User.newBuilder()
                .setId(VFSConstants.NEW_USER)
                .setLogin("nikita")
                .build();
        when(userManager.getUser()).thenReturn(user);

        CommandLine cmd = new CommandLine(userManager, networkManager);
        cmd.execute("default");

        verify(networkManager, atLeastOnce()).getMessageSender();
        verify(userManager, atLeastOnce()).getUser();
        verify(messageSender, atLeastOnce()).send(user, "default");
    }

    @Test
    public void testConnectCommandHandlesConnectExceptionAndPrintsWarning() throws Exception {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(userManager.isAuthorized()).thenReturn(false);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        doThrow(new ConnectException("refused")).when(networkManager).openSocket("localhost", "4499");

        CommandLine cmd = new CommandLine(userManager, networkManager);

        String errOutput = withCapturedErr(() -> cmd.execute("connect localhost:4499 nikita"));

        Assertions.assertTrue(errOutput.contains("Warning : Server is unavailable or you typed wrong host:port."));
        verify(userManager, never()).setUser(any());
        verify(messageSender, never()).send(any(), anyString());
    }

    @Test
    public void testConnectCommandHandlesIoExceptionAndPrintsError() throws Exception {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(userManager.isAuthorized()).thenReturn(false);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        doThrow(new IOException("boom")).when(networkManager).openSocket("localhost", "4499");

        CommandLine cmd = new CommandLine(userManager, networkManager);

        String errOutput = withCapturedErr(() -> cmd.execute("connect localhost:4499 nikita"));

        Assertions.assertTrue(errOutput.contains("CommandLine.IOException.Message=boom"));
        verify(userManager, never()).setUser(any());
        verify(messageSender, never()).send(any(), anyString());
    }

    @Test
    public void testExitCommandSendsQuitWhenAuthorized() {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        when(userManager.isAuthorized()).thenReturn(true);
        Protocol.User user = Protocol.User.newBuilder()
                .setId(VFSConstants.NEW_USER)
                .setLogin("nikita")
                .build();
        when(userManager.getUser()).thenReturn(user);

        CommandLine cmd = new CommandLine(userManager, networkManager);

        QuitException thrown = Assertions.assertThrows(QuitException.class, () -> cmd.execute("exit"));

        Assertions.assertEquals("Successful exit!", thrown.getMessage());
        verify(messageSender, atLeastOnce()).send(user, "quit");
    }

    @Test
    public void testExitCommandThrowsWithoutSendingWhenNotAuthorized() {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        when(userManager.isAuthorized()).thenReturn(false);

        CommandLine cmd = new CommandLine(userManager, networkManager);

        QuitException thrown = Assertions.assertThrows(QuitException.class, () -> cmd.execute("exit"));

        Assertions.assertEquals("Successful exit!", thrown.getMessage());
        verify(messageSender, never()).send(any(), anyString());
    }

    @Test
    public void testUnknownCommandRoutesToDefaultWithTrimmedSource() {
        NetworkManager networkManager = mock(NetworkManager.class);
        UserManager userManager = mock(UserManager.class);
        MessageSender messageSender = mock(MessageSender.class);
        when(networkManager.getMessageSender()).thenReturn(messageSender);
        when(userManager.isAuthorized()).thenReturn(true);
        Protocol.User user = Protocol.User.newBuilder()
                .setId(VFSConstants.NEW_USER)
                .setLogin("nikita")
                .build();
        when(userManager.getUser()).thenReturn(user);

        CommandLine cmd = new CommandLine(userManager, networkManager);
        cmd.execute("   stats   ");

        verify(messageSender, atLeastOnce()).send(user, "stats");
    }

    private String withCapturedErr(Runnable runnable) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent, true, StandardCharsets.UTF_8));
        try {
            runnable.run();
        } finally {
            System.setErr(originalErr);
        }
        return errContent.toString(StandardCharsets.UTF_8);
    }
}
