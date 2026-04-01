package dev.nklip.javacraft.vfs.server;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import dev.nklip.javacraft.vfs.core.network.protocol.RequestFactory;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol;
import dev.nklip.javacraft.vfs.core.exceptions.QuitException;
import dev.nklip.javacraft.vfs.server.command.Command;
import dev.nklip.javacraft.vfs.server.model.Timer;
import dev.nklip.javacraft.vfs.server.model.UserSession;
import dev.nklip.javacraft.vfs.server.network.ClientWriter;
import dev.nklip.javacraft.vfs.server.service.NodeService;
import dev.nklip.javacraft.vfs.server.service.UserSessionService;
import dev.nklip.javacraft.vfs.server.util.NodePrinter;

import java.util.Map;

import org.mockito.ArgumentCaptor;


import static org.mockito.Mockito.*;

/**
 * All common tests, except LockTests.
 * @author Lipatov Nikita
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Application.class)
@ActiveProfiles(value = "test")
@TestMethodOrder(MethodOrderer.MethodName.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class CommandLineTest {

    @Autowired
    private Map<String, Command> commands;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private UserSessionService userSessionService;
    @Autowired
    private NodePrinter nodePrinter;

    private UserSession nikitaSession;
    private UserSession r2d2Session;

    @BeforeEach
    public void setUp() {
        nodeService.initDirs();

        // UserSession #1
        ClientWriter clientWriter1 = mock(ClientWriter.class);
        UserSession userSession1 = userSessionService.startSession(clientWriter1, new Timer());
        String id1 = userSession1.getUser().getId();
        String login1 = "nikita";
        userSessionService.attachUser(id1, login1);
        nikitaSession = userSession1;

        // UserSession #2
        ClientWriter clientWriter2 = mock(ClientWriter.class);
        UserSession userSession2 = userSessionService.startSession(clientWriter2, new Timer());
        String id2 = userSession2.getUser().getId();
        String login2 = "r2d2";
        userSessionService.attachUser(id2, login2);
        r2d2Session = userSession2;
    }

    @Test
    public void testChangeDirectory() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);
        String actualResponse;

        actualResponse = okResponse(nikitaSession, cmd, id, login, "cd ../..");
        Assertions.assertEquals("/", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "cd ../..");
        Assertions.assertEquals("/", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "cd");
        Assertions.assertEquals("/", actualResponse);

        Assertions.assertEquals(
                """
                /
                |__home
                |  |__nikita
                |  |__r2d2
                """,
                nodePrinter.print(nodeService.getRoot())
        );

        actualResponse = okResponse(nikitaSession, cmd, id, login, "mkfile test_file.txt");
        Assertions.assertEquals("New file '/test_file.txt' was created!", actualResponse);

        Assertions.assertEquals(
                """
                /
                |__home
                |  |__nikita
                |  |__r2d2
                |__test_file.txt
                """,
                nodePrinter.print(nodeService.getRoot())
        );

        actualResponse = failResponse(nikitaSession, cmd, id, login, "cd test_file.txt");
        Assertions.assertEquals("Destination node is file!", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "cd not_existing_folder");
        Assertions.assertEquals("Destination node is not found!", actualResponse);

    }

    @Test
    public void testConnect() {
        ClientWriter nikitaWriter = nikitaSession.getClientWriter();
        ClientWriter r2d2Writer = r2d2Session.getClientWriter();
        clearInvocations(nikitaWriter, r2d2Writer);

        CommandLine cmd = new CommandLine(commands);

        ClientWriter newUserWriter = mock(ClientWriter.class);
        UserSession newUserSession = userSessionService.startSession(newUserWriter, new Timer());
        String newUserId = newUserSession.getUser().getId();
        cmd.onUserInput(newUserSession, RequestFactory.newRequest(newUserId, "", "connect neo"));

        ArgumentCaptor<Protocol.Response> successCaptor = ArgumentCaptor.forClass(Protocol.Response.class);
        verify(newUserWriter, times(1)).send(successCaptor.capture());
        Assertions.assertEquals(Protocol.Response.ResponseType.SUCCESS_CONNECT, successCaptor.getValue().getCode());
        Assertions.assertEquals("/home/neo", successCaptor.getValue().getMessage());
        Assertions.assertEquals(newUserId, successCaptor.getValue().getSpecificCode());

        // already existing user 1 get notification
        ArgumentCaptor<Protocol.Response> nikitaNotifyCaptor = ArgumentCaptor.forClass(Protocol.Response.class);
        verify(nikitaWriter, times(1)).send(nikitaNotifyCaptor.capture());
        Assertions.assertEquals(Protocol.Response.ResponseType.OK, nikitaNotifyCaptor.getValue().getCode());
        Assertions.assertEquals("User 'neo' has connected to server!", nikitaNotifyCaptor.getValue().getMessage());

        // already existing user 2 get notification
        ArgumentCaptor<Protocol.Response> r2d2NotifyCaptor = ArgumentCaptor.forClass(Protocol.Response.class);
        verify(r2d2Writer, times(1)).send(r2d2NotifyCaptor.capture());
        Assertions.assertEquals(Protocol.Response.ResponseType.OK, r2d2NotifyCaptor.getValue().getCode());
        Assertions.assertEquals("User 'neo' has connected to server!", r2d2NotifyCaptor.getValue().getMessage());

        // confirm user session has newly connected user
        Assertions.assertEquals("neo", userSessionService.getSession(newUserId).getUser().getLogin());

        // try to connect again under the same name
        ClientWriter duplicateUserWriter = mock(ClientWriter.class);
        UserSession duplicateUserSession = userSessionService.startSession(duplicateUserWriter, new Timer());
        String duplicateUserId = duplicateUserSession.getUser().getId();
        QuitException exception = Assertions.assertThrows(
                QuitException.class,
                () -> cmd.onUserInput(duplicateUserSession, RequestFactory.newRequest(duplicateUserId, "", "connect nikita"))
        );
        Assertions.assertEquals("Such user already exist!", exception.getMessage());

        ArgumentCaptor<Protocol.Response> failCaptor = ArgumentCaptor.forClass(Protocol.Response.class);
        verify(duplicateUserWriter, times(1)).send(failCaptor.capture());
        Assertions.assertEquals(Protocol.Response.ResponseType.FAIL_CONNECT, failCaptor.getValue().getCode());
        Assertions.assertEquals("Such user already exits. Please, change the login!", failCaptor.getValue().getMessage());
        Assertions.assertEquals("", failCaptor.getValue().getSpecificCode());
    }

    @Test
    public void testCopy() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);

        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "cd ../.."));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkdir applications/servers/weblogic"));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkdir logs"));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "copy applications logs"));

        Assertions.assertEquals(
                """
                        /
                        |__applications
                        |  |__servers
                        |  |  |__weblogic
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        |__logs
                        |  |__applications
                        |  |  |__servers
                        |  |  |  |__weblogic
                        """,
                nodePrinter.print(nodeService.getRoot())
        );

        String actualResponse = failResponse(nikitaSession, cmd, id, login, "copy not_existing not_existing");
        Assertions.assertEquals("Source path/node is not found!", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "copy home not_existing");
        Assertions.assertEquals("Destination path/node is not found!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "lock logs/applications");
        Assertions.assertEquals("You has locked the node by path '/logs/applications'", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "mkfile copy_test.txt");
        Assertions.assertEquals("New file '/copy_test.txt' was created!", actualResponse);

        Assertions.assertEquals(
                """
                        /
                        |__applications
                        |  |__servers
                        |  |  |__weblogic
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        |__logs
                        |  |__applications [Locked by nikita ]
                        |  |  |__servers
                        |  |  |  |__weblogic
                        |__copy_test.txt
                        """,
                nodePrinter.print(nodeService.getRoot())
        );

        actualResponse = okResponse(nikitaSession, cmd, id, login, "copy copy_test.txt logs/applications");
        Assertions.assertEquals("Node or children nodes is/are locked!", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "copy home/nikita copy_test.txt");
        Assertions.assertEquals("Destination path is not directory", actualResponse);
    }

    @Test
    public void testHelp() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);

        String actualResponse = okResponse(nikitaSession, cmd, id, login, "help");

        Assertions.assertEquals("""
                You can use next commands:
                    * - cd directory\s
                    * - connect server_name:port login\s
                    * - copy node directory\s
                    * - help\s
                    * - lock [-r] node\s
                        -r - enable recursive mode\s
                    * - mkdir directory\s
                    * - mkfile file
                    * - move node directory\s
                    * - print\s
                    * - quit\s
                    * - rename node name\s
                    * - rm node\s
                    * - unlock [-r] node\s
                        -r - enable recursive mode\s
                """, actualResponse);
    }

    @Test
    public void testLock() {
        String id1 = nikitaSession.getUser().getId();
        String login1 = nikitaSession.getUser().getLogin();
        String id2 = r2d2Session.getUser().getId();
        String login2 = r2d2Session.getUser().getLogin();
        CommandLine cmd1 = new CommandLine(commands);
        CommandLine cmd2 = new CommandLine(commands);

        cmd1.onUserInput(nikitaSession, RequestFactory.newRequest(id1, login1, "cd ../.."));
        cmd2.onUserInput(r2d2Session,   RequestFactory.newRequest(id2, login2, "cd ../.."));

        cmd1.onUserInput(nikitaSession, RequestFactory.newRequest(id1, login1, "mkfile applications/servers/weblogic/logs/weblogic.log"));
        cmd2.onUserInput(r2d2Session, RequestFactory.newRequest(id2, login2, "mkfile applications/databases/oracle/bin/oracle.exe"));

        Assertions.assertEquals(
                """
                        /
                        |__applications
                        |  |__databases
                        |  |  |__oracle
                        |  |  |  |__bin
                        |  |  |  |  |__oracle.exe
                        |  |__servers
                        |  |  |__weblogic
                        |  |  |  |__logs
                        |  |  |  |  |__weblogic.log
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        """,
                nodePrinter.print(nodeService.getRoot())
        );

        cmd1.onUserInput(nikitaSession, RequestFactory.newRequest(id1, login1, "lock applications/databases"));

        Assertions.assertEquals(
                """
                /
                |__applications
                |  |__databases [Locked by nikita ]
                |  |  |__oracle
                |  |  |  |__bin
                |  |  |  |  |__oracle.exe
                |  |__servers
                |  |  |__weblogic
                |  |  |  |__logs
                |  |  |  |  |__weblogic.log
                |__home
                |  |__nikita
                |  |__r2d2
                """,
                nodePrinter.print(nodeService.getRoot())
        );

        cmd2.onUserInput(r2d2Session, RequestFactory.newRequest(id2, login2, "lock applications"));

        Assertions.assertEquals(
                """
                /
                |__applications [Locked by r2d2 ]
                |  |__databases [Locked by nikita ]
                |  |  |__oracle
                |  |  |  |__bin
                |  |  |  |  |__oracle.exe
                |  |__servers
                |  |  |__weblogic
                |  |  |  |__logs
                |  |  |  |  |__weblogic.log
                |__home
                |  |__nikita
                |  |__r2d2
                """,
                nodePrinter.print(nodeService.getRoot())
        );


        String actualResponse = failResponse(r2d2Session, cmd2, id2, login2, "lock not_existing_folder");
        Assertions.assertEquals("Destination node is not found!", actualResponse);
    }

    @Test
    public void testLockRecursive() {
        String id1 = nikitaSession.getUser().getId();
        String login1 = nikitaSession.getUser().getLogin();
        String id2 = r2d2Session.getUser().getId();
        String login2 = r2d2Session.getUser().getLogin();
        CommandLine cmd1 = new CommandLine(commands);
        CommandLine cmd2 = new CommandLine(commands);

        cmd1.onUserInput(nikitaSession, RequestFactory.newRequest(id1, login1, "cd ../.."));
        cmd2.onUserInput(r2d2Session,   RequestFactory.newRequest(id2, login2, "cd ../.."));

        cmd1.onUserInput(nikitaSession, RequestFactory.newRequest(id1, login1, "mkfile applications/servers/weblogic/logs/weblogic.log"));
        cmd2.onUserInput(r2d2Session,   RequestFactory.newRequest(id2, login2, "mkfile applications/databases/oracle/bin/oracle.exe"));

        Assertions.assertEquals(
                """
                        /
                        |__applications
                        |  |__databases
                        |  |  |__oracle
                        |  |  |  |__bin
                        |  |  |  |  |__oracle.exe
                        |  |__servers
                        |  |  |__weblogic
                        |  |  |  |__logs
                        |  |  |  |  |__weblogic.log
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        """,
                nodePrinter.print(nodeService.getRoot())
        );

        cmd1.onUserInput(nikitaSession, RequestFactory.newRequest(id1, login1, "lock -r applications/databases"));

        Assertions.assertEquals(
                """
                /
                |__applications
                |  |__databases [Locked by nikita ]
                |  |  |__oracle [Locked by nikita ]
                |  |  |  |__bin [Locked by nikita ]
                |  |  |  |  |__oracle.exe [Locked by nikita ]
                |  |__servers
                |  |  |__weblogic
                |  |  |  |__logs
                |  |  |  |  |__weblogic.log
                |__home
                |  |__nikita
                |  |__r2d2
                """,
                nodePrinter.print(nodeService.getRoot())
        );

        cmd2.onUserInput(r2d2Session, RequestFactory.newRequest(id2, login2, "lock -r applications"));

        Assertions.assertEquals(
                """
                /
                |__applications
                |  |__databases [Locked by nikita ]
                |  |  |__oracle [Locked by nikita ]
                |  |  |  |__bin [Locked by nikita ]
                |  |  |  |  |__oracle.exe [Locked by nikita ]
                |  |__servers
                |  |  |__weblogic
                |  |  |  |__logs
                |  |  |  |  |__weblogic.log
                |__home
                |  |__nikita
                |  |__r2d2
                """,
                nodePrinter.print(nodeService.getRoot())
        );
    }

    @Test
    public void testMakeDirectory() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);

        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "cd ../.."));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkdir applications/servers/weblogic"));

        Assertions.assertEquals(
                """
                        /
                        |__applications
                        |  |__servers
                        |  |  |__weblogic
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        """,
                nodePrinter.print(nodeService.getRoot())
        );

        String actualResponse = failResponse(nikitaSession, cmd, id, login, "mkdir applications/servers/weblogic");
        Assertions.assertEquals("New directory could not be created!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "lock applications");
        Assertions.assertEquals("You has locked the node by path '/applications'", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "mkdir applications/new_folder");
        Assertions.assertEquals(
                "Parent node /applications is locked! Please wait until this node will be unlocked!",
                actualResponse
        );

        actualResponse = okResponse(nikitaSession, cmd, id, login, "mkdir logs");
        Assertions.assertEquals("New directory '/logs' was created!", actualResponse);

        Assertions.assertEquals(
                """
                        /
                        |__applications [Locked by nikita ]
                        |  |__servers
                        |  |  |__weblogic
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        |__logs
                        """,
                nodePrinter.print(nodeService.getRoot())
        );
    }

    @Test
    public void testMakeFile() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);

        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "cd ../.."));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkfile logs/app.log"));

        Assertions.assertEquals(
                """
                        /
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        |__logs
                        |  |__app.log
                        """,
                nodePrinter.print(nodeService.getRoot())
        );

        String actualResponse = failResponse(nikitaSession, cmd, id, login, "mkfile logs/app.log");
        Assertions.assertEquals("New file could not be created!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "lock logs");
        Assertions.assertEquals("You has locked the node by path '/logs'", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "mkfile logs/error.log");
        Assertions.assertEquals(
                "Parent node /logs is locked! Please wait until this node will be unlocked!",
                actualResponse
        );
    }

    @Test
    public void testMove() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);

        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "cd ../.."));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkdir applications/servers/weblogic"));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkdir logs"));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkfile destination.txt"));

        String actualResponse = failResponse(nikitaSession, cmd, id, login, "move not_existing logs");
        Assertions.assertEquals("Source path/node not found!", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "move applications not_existing");
        Assertions.assertEquals("Destination path/node not found!", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "move applications destination.txt");
        Assertions.assertEquals("Destination path is not directory", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "lock logs");
        Assertions.assertEquals("You has locked the node by path '/logs'", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "move applications logs");
        Assertions.assertEquals("Node or children nodes is/are locked!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "unlock logs");
        Assertions.assertEquals("Node '/logs' was unlocked!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "move applications logs");
        Assertions.assertEquals(
                "You has moved source node by path '/applications' to destination node by path '/logs'",
                actualResponse
        );

        Assertions.assertEquals(
                """
                        /
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        |__logs
                        |  |__applications
                        |  |  |__servers
                        |  |  |  |__weblogic
                        |__destination.txt
                        """,
                nodePrinter.print(nodeService.getRoot())
        );
    }

    @Test
    public void testPrint() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);

        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "cd ../.."));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkdir applications/servers"));

        String actualResponse = okResponse(nikitaSession, cmd, id, login, "lock applications");
        Assertions.assertEquals("You has locked the node by path '/applications'", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "print");
        Assertions.assertEquals(
                """
                /
                |__applications [Locked by nikita ]
                |  |__servers
                |__home
                |  |__nikita
                |  |__r2d2
                """,
                actualResponse
        );
    }

    @Test
    public void testQuit() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        ClientWriter nikitaWriter = nikitaSession.getClientWriter();
        ClientWriter r2d2Writer = r2d2Session.getClientWriter();
        CommandLine cmd = new CommandLine(commands);

        clearInvocations(nikitaWriter, r2d2Writer);

        QuitException exception = Assertions.assertThrows(
                QuitException.class,
                () -> cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "quit"))
        );
        Assertions.assertEquals("User 'nikita' has been disconnected", exception.getMessage());
        Assertions.assertNull(userSessionService.getSession(id));

        ArgumentCaptor<Protocol.Response> ownResponseCaptor =
                ArgumentCaptor.forClass(Protocol.Response.class);
        verify(nikitaWriter, times(1)).send(ownResponseCaptor.capture());
        Assertions.assertEquals(
                Protocol.Response.ResponseType.SUCCESS_QUIT,
                ownResponseCaptor.getValue().getCode()
        );
        Assertions.assertEquals(
                "You are disconnected from server!",
                ownResponseCaptor.getValue().getMessage()
        );

        // confirm that the other user also was notified about that action
        ArgumentCaptor<Protocol.Response> notificationCaptor =
                ArgumentCaptor.forClass(Protocol.Response.class);
        verify(r2d2Writer, times(1)).send(notificationCaptor.capture());
        Assertions.assertEquals(
                Protocol.Response.ResponseType.OK,
                notificationCaptor.getValue().getCode()
        );
        Assertions.assertEquals(
                "User 'nikita' has been disconnected",
                notificationCaptor.getValue().getMessage()
        );
    }

    @Test
    public void testRemove() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);

        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "cd ../.."));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkdir applications/servers/weblogic"));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkdir logs"));

        String actualResponse = failResponse(nikitaSession, cmd, id, login, "rm not_existing");
        Assertions.assertEquals("Node is not found!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "lock applications");
        Assertions.assertEquals("You has locked the node by path '/applications'", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "rm applications");
        Assertions.assertEquals("Node or children nodes is / are locked!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "unlock applications");
        Assertions.assertEquals("Node '/applications' was unlocked!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "rm applications");
        Assertions.assertEquals("Node 'applications' was deleted! Removal status = 'true'", actualResponse);

        Assertions.assertEquals(
                """
                        /
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        |__logs
                        """,
                nodePrinter.print(nodeService.getRoot())
        );

    }

    @Test
    public void testRename() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);

        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "cd ../.."));
        cmd.onUserInput(nikitaSession, RequestFactory.newRequest(id, login, "mkfile applications/servers/weblogic/logs/weblogic.log"));

        String actualResponse = failResponse(nikitaSession, cmd, id, login, "rename not_existing new_name");
        Assertions.assertEquals("Node is not found!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "lock applications/servers/weblogic");
        Assertions.assertEquals("You has locked the node by path '/applications/servers/weblogic'", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd, id, login, "rename applications/servers/weblogic web-servers");
        Assertions.assertEquals("Node is locked!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "unlock applications/servers/weblogic");
        Assertions.assertEquals("Node '/applications/servers/weblogic' was unlocked!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd, id, login, "rename applications/servers/weblogic web-servers");
        Assertions.assertEquals("Node 'weblogic' was renamed to 'web-servers'", actualResponse);

        Assertions.assertEquals(
                """
                        /
                        |__applications
                        |  |__servers
                        |  |  |__web-servers
                        |  |  |  |__logs
                        |  |  |  |  |__weblogic.log
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        """,
                nodePrinter.print(nodeService.getRoot())
        );
    }

    @Test
    public void testUnlock() {
        String id1 = nikitaSession.getUser().getId();
        String login1 = nikitaSession.getUser().getLogin();
        String id2 = r2d2Session.getUser().getId();
        String login2 = r2d2Session.getUser().getLogin();
        CommandLine cmd1 = new CommandLine(commands);
        CommandLine cmd2 = new CommandLine(commands);

        cmd1.onUserInput(nikitaSession, RequestFactory.newRequest(id1, login1, "cd ../.."));
        cmd2.onUserInput(r2d2Session, RequestFactory.newRequest(id2, login2, "cd ../.."));
        cmd1.onUserInput(nikitaSession, RequestFactory.newRequest(id1, login1, "mkfile applications/servers/weblogic/logs/weblogic.log"));

        String actualResponse = okResponse(nikitaSession, cmd1, id1, login1, "unlock not_existing");
        Assertions.assertEquals("Node is not found!", actualResponse);

        actualResponse = failResponse(nikitaSession, cmd1, id1, login1, "unlock applications");
        Assertions.assertEquals("Node is already unlocked!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd1, id1, login1, "lock applications/servers");
        Assertions.assertEquals("You has locked the node by path '/applications/servers'", actualResponse);

        actualResponse = okResponse(r2d2Session, cmd2, id2, login2, "unlock applications/servers");
        Assertions.assertEquals("Node is locked by different user!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd1, id1, login1, "unlock applications/servers");
        Assertions.assertEquals("Node '/applications/servers' was unlocked!", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd1, id1, login1, "lock -r applications");
        Assertions.assertEquals("You has locked the node by path '/applications'", actualResponse);

        actualResponse = okResponse(nikitaSession, cmd1, id1, login1, "unlock -r applications");
        Assertions.assertEquals("Node '/applications' was unlocked!", actualResponse);

        Assertions.assertEquals(
                """
                        /
                        |__applications
                        |  |__servers
                        |  |  |__weblogic
                        |  |  |  |__logs
                        |  |  |  |  |__weblogic.log
                        |__home
                        |  |__nikita
                        |  |__r2d2
                        """,
                nodePrinter.print(nodeService.getRoot())
        );
    }

    @Test
    public void testWhoami() {
        String id1 = nikitaSession.getUser().getId();
        String login1 = nikitaSession.getUser().getLogin();
        String id2 = r2d2Session.getUser().getId();
        String login2 = r2d2Session.getUser().getLogin();
        CommandLine cmd1 = new CommandLine(commands);
        CommandLine cmd2 = new CommandLine(commands);

        String actualResponse = okResponse(nikitaSession, cmd1, id1, login1, "whoami");
        Assertions.assertEquals("nikita", actualResponse);

        actualResponse = okResponse(r2d2Session, cmd2, id2, login2, "whoami");
        Assertions.assertEquals("r2d2", actualResponse);
    }

    @Test
    public void testNoSuchCommand() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        CommandLine cmd = new CommandLine(commands);

        String actualResponse = okResponse(nikitaSession, cmd, id, login, "unknown-command");
        Assertions.assertEquals(
                "No such command! Please check you syntax or type 'help'!",
                actualResponse
        );
    }

    @Test
    public void testExceptionCausedByCommand() {
        String id = nikitaSession.getUser().getId();
        String login = nikitaSession.getUser().getLogin();
        Command failingCommand = mock(Command.class);
        doThrow(new IllegalArgumentException("exception caused by a command")).when(failingCommand)
                .apply(eq(nikitaSession), any());
        CommandLine cmd = new CommandLine(Map.of("boom", failingCommand));

        String actualResponse = failResponse(nikitaSession, cmd, id, login, "boom");
        Assertions.assertEquals("exception caused by a command", actualResponse);
    }

    private String okResponse(
            UserSession userSession,
            CommandLine cmd,
            String id,
            String login,
            String command) {
        clearInvocations(userSession.getClientWriter());

        cmd.onUserInput(userSession, RequestFactory.newRequest(id, login, command));

        ArgumentCaptor<Protocol.Response> responseCaptor = ArgumentCaptor.forClass(Protocol.Response.class);
        verify(userSession.getClientWriter(), times(1)).send(responseCaptor.capture());
        Assertions.assertEquals(
                Protocol.Response.ResponseType.OK,
                responseCaptor.getValue().getCode()
        );
        return responseCaptor.getValue().getMessage();
    }

    private String failResponse(
            UserSession userSession,
            CommandLine cmd,
            String id,
            String login,
            String command) {
        clearInvocations(userSession.getClientWriter());

        cmd.onUserInput(userSession, RequestFactory.newRequest(id, login, command));

        ArgumentCaptor<Protocol.Response> responseCaptor =
                ArgumentCaptor.forClass(Protocol.Response.class);
        verify(userSession.getClientWriter(), times(1)).send(responseCaptor.capture());
        Assertions.assertEquals(
                Protocol.Response.ResponseType.FAIL,
                responseCaptor.getValue().getCode()
        );
        return responseCaptor.getValue().getMessage();
    }

}
