package dev.nklip.vfs.server.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import dev.nklip.vfs.core.network.protocol.Protocol.Response;
import dev.nklip.vfs.core.network.protocol.Protocol.Response.ResponseType;
import dev.nklip.vfs.core.network.protocol.Protocol.User;
import dev.nklip.vfs.server.model.Timer;
import dev.nklip.vfs.server.model.UserSession;
import dev.nklip.vfs.server.network.ClientWriter;

/**
 * @author Lipatov Nikita
 */
public class UserSessionServiceTest {

    @Test
    public void testGetSession() {
        LockService lockService = new LockService();
        NodeManager nodeManager = new NodeManager(lockService);
        NodeService nodeService = new NodeService("/", lockService, nodeManager);
        nodeService.initDirs();
        UserSessionService userSessionService = new UserSessionService(nodeService, lockService);

        Assertions.assertNull(userSessionService.getSession(""));

        // UserSession #1
        ClientWriter nikitaCWMock = Mockito.mock(ClientWriter.class);

        UserSession userSession = userSessionService.startSession(nikitaCWMock, new Timer());
        userSessionService.attachUser(userSession.getUser().getId(), "nikita");

        Assertions.assertNotNull(userSessionService.getSession(userSession.getUser().getId()));
    }

    @Test
    public void testStopSession() {
        LockService lockService = new LockService();
        NodeManager nodeManager = new NodeManager(lockService);
        NodeService nodeService = new NodeService("/", lockService, nodeManager);
        nodeService.initDirs();
        UserSessionService userSessionService = new UserSessionService(nodeService, lockService);

        // UserSession #1
        ClientWriter nikitaCWMock = Mockito.mock(ClientWriter.class);

        UserSession userSession = userSessionService.startSession(nikitaCWMock, new Timer());
        userSessionService.attachUser(userSession.getUser().getId(), "nikita");

        Assertions.assertEquals(1, userSessionService.getRegistry().size());
        userSessionService.stopSession(userSession.getUser().getId());
        Assertions.assertEquals(0, userSessionService.getRegistry().size());
        userSessionService.stopSession(userSession.getUser().getId());
        Assertions.assertEquals(0, userSessionService.getRegistry().size());
    }

    @Test
    public void testIsLogged() {
        NodeService nodeService = Mockito.mock(NodeService.class);
        LockService lockService = Mockito.mock(LockService.class);
        UserSessionService userSessionService = new UserSessionService(nodeService, lockService);

        UserSession nikitaSession = session("nikita-id", "nikita", Mockito.mock(ClientWriter.class));
        UserSession emptyLoginSession = session("empty-id", "", Mockito.mock(ClientWriter.class));

        userSessionService.getRegistry().put("nikita-id", nikitaSession);
        userSessionService.getRegistry().put("empty-id", emptyLoginSession);

        Assertions.assertTrue(userSessionService.isLogged("nikita"));
        Assertions.assertFalse(userSessionService.isLogged("unknown"));
    }

    @Test
    public void testNotifyUsers() {
        NodeService nodeService = Mockito.mock(NodeService.class);
        LockService lockService = Mockito.mock(LockService.class);
        UserSessionService userSessionService = new UserSessionService(nodeService, lockService);

        ClientWriter myWriter = Mockito.mock(ClientWriter.class);
        ClientWriter anotherWriter = Mockito.mock(ClientWriter.class);
        ClientWriter emptyLoginWriter = Mockito.mock(ClientWriter.class);

        userSessionService.getRegistry().put("my-id", session("my-id", "nikita", myWriter));
        userSessionService.getRegistry().put("another-id", session("another-id", "r2d2", anotherWriter));
        userSessionService.getRegistry().put("empty-id", session("empty-id", "", emptyLoginWriter));

        userSessionService.notifyUsers("my-id", "hello");

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        Mockito.verify(anotherWriter, Mockito.atLeastOnce()).send(responseCaptor.capture());
        Assertions.assertEquals(ResponseType.OK, responseCaptor.getValue().getCode());
        Assertions.assertEquals("hello", responseCaptor.getValue().getMessage());

        Mockito.verify(myWriter, Mockito.never()).send(Mockito.any(Response.class));
        Mockito.verify(emptyLoginWriter, Mockito.never()).send(Mockito.any(Response.class));
    }

    private UserSession session(String id, String login, ClientWriter clientWriter) {
        User user = User.newBuilder()
                .setId(id)
                .setLogin(login)
                .build();
        return new UserSession(user, new Timer(), clientWriter);
    }

}
