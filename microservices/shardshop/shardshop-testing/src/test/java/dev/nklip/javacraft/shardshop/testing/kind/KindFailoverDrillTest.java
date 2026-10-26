package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.Test;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KindFailoverDrillTest {
    private static final JsonNode SAVED = Json.object("{\"targetRate\":12.5,\"paused\":false}");

    @Test void lostRateResponseReopensTheTunnelAndReplaysTheExactSavedControls() throws Exception {
        KindFailoverDrill.ControlSessions sessions = mock();
        KindFailoverDrill.ControlSession first = successful(), second = successful();
        when(first.exchange(eq("PUT"), eq("/rate"), any())).thenThrow(new java.io.EOFException("Lost committed setter response"));
        when(sessions.open()).thenReturn(first, second);

        KindFailoverDrill.restoreControls(sessions, SAVED, new FakeTime());

        verify(sessions, times(2)).open();
        verify(first).close();
        verify(second).exchange("PUT", "/rate", Json.object("{\"rate\":12.5}"));
        verify(second).exchange("POST", "/resume", null);
        verify(second).exchange("GET", "", null);
        verify(second).close();
    }

    @Test void partialResumeFailureRetriesTheSameSavedRateAndPauseState() throws Exception {
        KindFailoverDrill.ControlSessions sessions = mock();
        KindFailoverDrill.ControlSession first = successful(), second = successful();
        when(first.exchange("POST", "/resume", null)).thenReturn(new KindFailoverDrill.ControlReply(503, Json.newObject()));
        when(sessions.open()).thenReturn(first, second);

        KindFailoverDrill.restoreControls(sessions, SAVED, new FakeTime());

        for (var session : List.of(first, second)) {
            verify(session).exchange("PUT", "/rate", Json.object("{\"rate\":12.5}"));
            verify(session).exchange("POST", "/resume", null);
            verify(session).close();
        }
    }

    @Test void permanentHttpFailuresAndIncorrectReadbackAreNotRetried() throws Exception {
        for (int status : List.of(403, 409, 500, 200)) {
            KindFailoverDrill.ControlSessions sessions = mock();
            KindFailoverDrill.ControlSession session = successful();
            when(sessions.open()).thenReturn(session);
            if (status == 200) when(session.exchange("GET", "", null)).thenReturn(
                    new KindFailoverDrill.ControlReply(200, Json.object("{\"targetRate\":12.5,\"paused\":true}")));
            else when(session.exchange(eq("PUT"), eq("/rate"), any())).thenReturn(new KindFailoverDrill.ControlReply(status, Json.newObject()));

            assertThrows(IOException.class, () -> KindFailoverDrill.restoreControls(sessions, SAVED, new FakeTime()));

            verify(sessions, times(1)).open();
            verify(session).close();
        }
    }

    @Test void transientRecoveryStopsAfterThreeAttemptsOrTheTotalDeadline() throws Exception {
        KindFailoverDrill.ControlSessions sessions = mock();
        when(sessions.open()).thenThrow(new java.net.ConnectException("Unavailable"));
        assertThrows(IOException.class, () -> KindFailoverDrill.restoreControls(sessions, SAVED, new FakeTime()));
        verify(sessions, times(3)).open();

        KindFailoverDrill.ControlSessions deadline = mock();
        when(deadline.open()).thenThrow(new java.net.ConnectException("Unavailable"));
        FakeTime time = new FakeTime();
        time.jump = Duration.ofSeconds(180).toNanos();
        assertThrows(IOException.class, () -> KindFailoverDrill.restoreControls(deadline, SAVED, time));
        verify(deadline, times(1)).open();
    }

    private static KindFailoverDrill.ControlSession successful() throws Exception {
        KindFailoverDrill.ControlSession session = mock();
        when(session.exchange(anyString(), anyString(), any())).thenReturn(new KindFailoverDrill.ControlReply(200, SAVED));
        return session;
    }

    private static final class FakeTime implements KindFailoverDrill.ControlTime {
        private long elapsed;
        private long jump;
        public long now() { return elapsed; }
        public void sleep(long nanos) { elapsed += jump == 0 ? nanos : jump; }
    }

    @Test
    void operatorMustMatchTheReviewedReleaseAndArtifactBeforeAnyFault() throws IOException {
        KindFailoverDrill.requireOperator(KindFailoverDrill.OPERATOR);
        assertThrows(IOException.class, () -> KindFailoverDrill.requireOperator("ghcr.io/cloudnative-pg/cloudnative-pg:1.30.1"));
        assertThrows(IOException.class, () -> KindFailoverDrill.requireOperator(KindFailoverDrill.OPERATOR.replace(":1.30.1@", ":1.31.0@")));
        assertThrows(IOException.class, () -> KindFailoverDrill.requireOperator(KindFailoverDrill.OPERATOR.replace("923c267e", "00000000")));
    }
}
