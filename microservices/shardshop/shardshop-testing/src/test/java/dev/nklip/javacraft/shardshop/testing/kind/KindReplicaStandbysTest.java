package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KindReplicaStandbysTest {
    @Test
    void fencingRecoversTheExactAnnotationAndChecksTopologyAndVolumes() throws Exception {
        assertFenceRecovery(false);
        assertFenceRecovery(true);
    }

    private void assertFenceRecovery(boolean explicitEmpty) throws Exception {
        Fixture fixture = new Fixture(explicitEmpty);
        KindReplicaStandbys standbys = fixture.helper();
        standbys.fence(List.of("shard-a-2"));
        verify(fixture.topology).endpoints("shard-a-ro", List.of("shard-a-3"));
        verify(fixture.lab).kubectl(Duration.ofSeconds(140), "--request-timeout=130s", "wait",
                "--for=condition=Ready=false", "pod/shard-a-2", "--timeout=120s");
        assertEquals("registered", fixture.events.getFirst());
        assertEquals("[\"shard-a-2\"]", fixture.fence());

        fixture.recoveries.getFirst().check();

        assertEquals(explicitEmpty, fixture.cluster.path("metadata").path("annotations").has("cnpg.io/fencedInstances"));
        if (explicitEmpty) assertEquals("[]", fixture.fence());
        verify(fixture.topology).ready("cluster/shard-a");
        verify(fixture.topology).ready("pod/shard-a-2");
        verify(fixture.topology).ready("pod/shard-a-3");
        verify(fixture.topology).streaming("shard-a-1", List.of("shard-a-2", "shard-a-3"));
        verify(fixture.topology).endpoints("shard-a-ro", List.of("shard-a-2", "shard-a-3"));
        verify(fixture.topology).volumes("shard-a");
    }

    @Test
    void lostFenceResponseStillRecoversTheIssuedFence() throws Exception {
        Fixture fixture = new Fixture(false);
        KindReplicaStandbys standbys = fixture.helper();
        fixture.losePatch = true;
        assertThrows(IOException.class, () -> standbys.fence(fixture.state.standbys()));
        assertEquals("[\"shard-a-2\",\"shard-a-3\"]", fixture.fence());
        fixture.recoveries.getFirst().check();
        assertFalse(fixture.cluster.path("metadata").path("annotations").has("cnpg.io/fencedInstances"));
    }

    @Test
    void foreignFenceAndPrimarySelectionsAreNeverOverwritten() throws Exception {
        Fixture fixture = new Fixture(false);
        KindReplicaStandbys standbys = fixture.helper();
        assertThrows(IOException.class, () -> standbys.fence(List.of("shard-a-1")));
        assertEquals(List.of("registered"), fixture.events);
        fixture.annotations().put("cnpg.io/fencedInstances", "[\"operator-instance\"]");
        assertThrows(IOException.class, () -> standbys.fence(List.of("shard-a-2")));
        assertThrows(IOException.class, standbys::restore);
        assertEquals("[\"operator-instance\"]", fixture.fence());
        assertEquals(List.of("registered"), fixture.events);
    }

    @Test
    void conditionalPatchRejectsAnOperatorUpdateBetweenReadAndMutation() throws Exception {
        Fixture fixture = new Fixture(false);
        KindReplicaStandbys standbys = fixture.helper();
        fixture.racePatch = true;
        assertThrows(IOException.class, () -> standbys.fence(List.of("shard-a-2")));
        assertThrows(IOException.class, standbys::restore);
        assertEquals("[\"operator-instance\"]", fixture.fence());
    }

    @Test
    void aReplacementClusterIsNeverMutatedDuringRecovery() throws Exception {
        Fixture fixture = new Fixture(false);
        KindReplicaStandbys standbys = fixture.helper();
        fixture.cluster.withObject("/metadata").put("uid", "replacement");
        assertThrows(IOException.class, standbys::restore);
        assertEquals(List.of("registered"), fixture.events);
    }

    @Test
    void preexistingReplayPauseIsRejectedBeforeAnyPauseMutation() throws Exception {
        Fixture fixture = new Fixture(false);
        KindReplicaStandbys standbys = fixture.helper();
        when(fixture.topology.sql("shard-a-3", "SELECT pg_is_in_recovery(), pg_get_wal_replay_pause_state()"))
                .thenReturn("t|paused");
        assertThrows(IOException.class, standbys::pauseReplay);
        verify(fixture.topology, never()).sql(anyString(), eq("SELECT pg_wal_replay_pause()"));
        assertEquals(1, fixture.recoveries.size(), "No owned replay change needs recovery");
    }

    @Test
    void lostPauseResponseIsRegisteredBeforeMutationAndResumesOnlyOwnedStandbys() throws Exception {
        Fixture fixture = new Fixture(false);
        KindReplicaStandbys standbys = fixture.helper();
        when(fixture.topology.sql("shard-a-2", "SELECT pg_wal_replay_pause()"))
                .thenAnswer(invocation -> {
                    assertEquals(2, fixture.recoveries.size());
                    throw new IOException("Lost pause response");
                });
        assertThrows(IOException.class, standbys::pauseReplay);
        fixture.recoveries.getLast().check();
        verify(fixture.topology).sql("shard-a-2", "SELECT pg_wal_replay_resume()");
        verify(fixture.topology, never()).sql("shard-a-3", "SELECT pg_wal_replay_resume()");
        standbys.resumeReplay();
        verify(fixture.topology, times(1)).sql("shard-a-2", "SELECT pg_wal_replay_resume()");
    }

    @Test
    void pauseAndResumeAwaitReplayStateAndTryOtherStandbyAfterRecoveryFailure() throws Exception {
        Fixture fixture = new Fixture(false);
        KindReplicaStandbys standbys = fixture.helper();
        standbys.pauseReplay();
        for (String standby : fixture.state.standbys()) {
            verify(fixture.topology).awaitSql(standby, "SELECT pg_get_wal_replay_pause_state()", "paused");
        }
        when(fixture.topology.sql("shard-a-2", "SELECT pg_wal_replay_resume()"))
                .thenThrow(new IOException("Unavailable standby")).thenReturn("");
        assertThrows(IOException.class, standbys::resumeReplay);
        verify(fixture.topology).sql("shard-a-3", "SELECT pg_wal_replay_resume()");
        verify(fixture.topology).awaitSql("shard-a-3", "SELECT pg_get_wal_replay_pause_state()", "not paused");
        standbys.resumeReplay();
        verify(fixture.topology, times(2)).sql("shard-a-2", "SELECT pg_wal_replay_resume()");
        verify(fixture.topology, times(1)).sql("shard-a-3", "SELECT pg_wal_replay_resume()");
    }

    private static final class Fixture {
        final KindLab lab = mock();
        final KindTopology topology = mock();
        final KindDrillGuard guard = mock();
        final KindTopology.Shard state = new KindTopology.Shard("shard-a", "shard-a-1",
                List.of("shard-a-2", "shard-a-3"), Map.of("data", new KindTopology.Volume("uid", "pv", "Bound")));
        final List<KindLab.Probe> recoveries = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final ObjectNode cluster;
        boolean losePatch;
        boolean racePatch;

        Fixture(boolean explicitEmpty) throws Exception {
            cluster = (ObjectNode) KindLab.parse("{\"metadata\":{\"uid\":\"cluster-uid\",\"resourceVersion\":\"1\"}}");
            if (explicitEmpty) annotations().put("cnpg.io/fencedInstances", "[]");
            when(topology.cluster("shard-a")).thenAnswer(invocation -> cluster.deepCopy());
            when(topology.primary("shard-a")).thenReturn("shard-a-1");
            when(topology.volumes("shard-a")).thenReturn(state.volumes());
            when(topology.sql(anyString(), eq("SELECT pg_is_in_recovery(), pg_get_wal_replay_pause_state()")))
                    .thenReturn("t|not paused");
            doAnswer(invocation -> {
                recoveries.add(invocation.getArgument(1));
                events.add("registered");
                return null;
            }).when(guard).onClose(anyString(), any(KindLab.Probe.class));
            when(lab.kubectl(any(String[].class))).thenAnswer(invocation -> {
                String[] arguments = (String[]) invocation.getRawArguments()[0];
                assertEquals("patch", arguments[0]);
                if (racePatch) {
                    racePatch = false;
                    annotations().put("cnpg.io/fencedInstances", "[\"operator-instance\"]");
                    cluster.withObject("/metadata").put("resourceVersion", "operator-update");
                }
                JsonNode patch = KindLab.parse(arguments[arguments.length - 1]);
                for (JsonNode operation : patch) {
                    String path = operation.path("path").asText();
                    if (operation.path("op").asText().equals("test")) {
                        if (!cluster.at(path).equals(operation.path("value"))) throw new IOException("Conditional patch conflict");
                    } else if (operation.path("op").asText().equals("remove")) {
                        annotations().remove("cnpg.io/fencedInstances");
                    } else if (path.equals("/metadata/annotations")) {
                        cluster.withObject("/metadata").set("annotations", operation.path("value").deepCopy());
                    } else {
                        annotations().put("cnpg.io/fencedInstances", operation.path("value").asText());
                    }
                }
                events.add("patch");
                if (losePatch) { losePatch = false; throw new IOException("Lost patch response"); }
                return "";
            });
        }

        KindReplicaStandbys helper() throws Exception { return new KindReplicaStandbys(lab, topology, state, guard); }
        ObjectNode annotations() { return cluster.withObject("/metadata/annotations"); }
        String fence() { return cluster.path("metadata").path("annotations").path("cnpg.io/fencedInstances").asText(); }
    }
}
