package dev.nklip.vfs.server.scheduler;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import dev.nklip.vfs.server.Application;
import dev.nklip.vfs.server.model.Timer;
import dev.nklip.vfs.server.model.UserSession;
import dev.nklip.vfs.server.network.ClientWriter;
import dev.nklip.vfs.server.service.UserSessionService;
import dev.nklip.vfs.core.network.protocol.Protocol.User;

/**
 * @author Lipatov Nikita
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Application.class)
@ActiveProfiles(value = "test")
public class TimeoutJobTest {

    private UserSession emptySession;
    private UserSession timeoutSession;
    private UserSession activeSession;
    private ClientWriter timeoutWriter;
    private ClientWriter activeWriter;

    @MockitoBean
    public UserSessionService userSessionService;

    @Autowired
    private TimeoutJob timeoutJob;

    @BeforeEach
    public void setUp() {
        emptySession = Mockito.mock(UserSession.class);
        timeoutSession = Mockito.mock(UserSession.class);
        activeSession = Mockito.mock(UserSession.class);
        timeoutWriter = Mockito.mock(ClientWriter.class);
        activeWriter = Mockito.mock(ClientWriter.class);
        Timer emptyTimer = Mockito.mock(Timer.class);
        Timer timeoutTimer = Mockito.mock(Timer.class);
        Timer activeTimer = Mockito.mock(Timer.class);

        User emptyUser = User.newBuilder().setId("empty-id").setLogin("").build();
        User timeoutUser = User.newBuilder().setId("nikita-id").setLogin("nikita").build();
        User activeUser = User.newBuilder().setId("r2d2-id").setLogin("r2d2").build();

        when(emptySession.getUser()).thenReturn(emptyUser);
        when(emptySession.getTimer()).thenReturn(emptyTimer);
        when(emptyTimer.difference()).thenReturn(1);

        when(timeoutSession.getUser()).thenReturn(timeoutUser);
        when(timeoutSession.getTimer()).thenReturn(timeoutTimer);
        when(timeoutSession.getClientWriter()).thenReturn(timeoutWriter);
        when(timeoutTimer.difference()).thenReturn(10);

        when(activeSession.getUser()).thenReturn(activeUser);
        when(activeSession.getTimer()).thenReturn(activeTimer);
        when(activeSession.getClientWriter()).thenReturn(activeWriter);
        when(activeTimer.difference()).thenReturn(0);

        Map<String, UserSession> registry = new LinkedHashMap<>();
        registry.put("empty-key", emptySession);
        registry.put("nikita-key", timeoutSession);
        registry.put("r2d2-key", activeSession);
        when(userSessionService.getRegistry()).thenReturn(registry);
    }

    @Test
    public void testTimeoutStopsEmptyAndExpiredSessionsWithoutWaiting() {
        timeoutJob.timeout();

        verify(userSessionService, times(1)).stopSession("empty-key");
        verify(userSessionService, times(1)).stopSession("nikita-key");
        verify(userSessionService, never()).stopSession("r2d2-key");
        verify(timeoutWriter, times(1)).send(Mockito.any());
        verify(activeWriter, never()).send(Mockito.any());
        verify(userSessionService, times(1))
                .notifyUsers(eq("nikita-id"), contains("disconnected from server by timeout"));
    }

    @Test
    public void testTimeoutReadsAllSessionsFromRegistry() {
        Assertions.assertEquals(3, userSessionService.getRegistry().size());

        timeoutJob.timeout();

        verify(emptySession, times(1)).getTimer();
        verify(timeoutSession, times(1)).getTimer();
        verify(activeSession, times(1)).getTimer();
    }
}
