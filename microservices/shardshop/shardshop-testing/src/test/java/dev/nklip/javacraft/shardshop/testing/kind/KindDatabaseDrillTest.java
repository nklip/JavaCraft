package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KindDatabaseDrillTest {
    @TempDir Path snapshot;

    @Test
    void drillShardDefaultsToFirstEntryAndRejectsUnknownOrBlankSelections() throws IOException {
        List<String> shards = List.of("west", "east");
        assertEquals("west", KindDatabaseDrill.selectShard(shards, null));
        assertEquals("east", KindDatabaseDrill.selectShard(shards, "east"));
        for (String invalid : List.of("", "unknown", "east ", "east'")) {
            assertThrows(IOException.class, () -> KindDatabaseDrill.selectShard(shards, invalid));
        }
    }

    @Test
    void standbyWriteMustFailWithReadOnlySqlstate() {
        assertTrue(KindDatabaseDrill.readOnlyRejection(new KindLab.CommandResult(1, "", "ERROR: 25006")));
        assertFalse(KindDatabaseDrill.readOnlyRejection(new KindLab.CommandResult(0, "", "ERROR: 25006")));
        assertFalse(KindDatabaseDrill.readOnlyRejection(new KindLab.CommandResult(1, "", "ERROR: 42501")));
        assertFalse(KindDatabaseDrill.readOnlyRejection(new KindLab.CommandResult(1, "", "ERROR: 1250060")));
    }

    @Test
    void preexistingFenceIsRejectedWithoutChangingItOrCreatingSqlObjects() throws Exception {
        Fixture fixture = new Fixture(false);
        when(fixture.topology.cluster("shard-a")).thenReturn(KindLab.parse("""
                {"metadata":{"annotations":{"cnpg.io/fencedInstances":"[\\\"shard-a-2\\\"]"}}}
                """));
        IOException failure = assertThrows(IOException.class, () -> fixture.drill.verify());
        assertTrue(failure.getMessage().contains("already has fenced"));
        assertFalse(fixture.schemaPresent.get());
        assertTrue(fixture.events.stream().noneMatch(event -> event.equals("unfence")));
    }

    @Test
    void lostSchemaCreationResponseStillRestoresQuorumAndRemovesPartiallyCreatedObjects() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.failCreate.set(true);
        assertThrows(IOException.class, () -> fixture.drill.verify());
        assertFalse(fixture.schemaPresent.get());
        assertEquals("[]", fixture.fence.get());
        assertTrue(fixture.events.indexOf("restore-quorum") < fixture.events.indexOf("drop-sql"));
        assertEquals("release-lock", fixture.events.getLast());
    }

    @Test
    void failedSyncRepObservationRestoresBothStandbysAndStopsWriterBeforeDroppingSql() throws Exception {
        Fixture fixture = new Fixture(true);
        IOException failure = assertThrows(IOException.class, () -> fixture.drill.verify());
        assertEquals("injected SyncRep observation failure", failure.getMessage());
        assertFalse(fixture.schemaPresent.get());
        assertFalse(fixture.writerSession.get());
        assertEquals("[]", fixture.fence.get());
        assertTrue(fixture.events.contains("fence-both"));
        assertTrue(fixture.events.indexOf("unfence") < fixture.events.indexOf("stop-writer"));
        assertTrue(fixture.events.indexOf("stop-writer") < fixture.events.indexOf("drop-sql"));
        assertEquals("release-lock", fixture.events.getLast());
    }

    @Test
    void recoveryPreservesAnExplicitEmptyFenceAnnotation() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.fencePresent.set(true);
        assertThrows(IOException.class, () -> fixture.drill.verify());
        assertEquals("[]", fixture.fence.get());
        assertTrue(fixture.fencePresent.get());
        assertFalse(fixture.schemaPresent.get());
        assertFalse(fixture.writerSession.get());
    }

    @Test
    void unrelatedFenceChangeIsPreservedAndFailedRecoveryKeepsTheDrillLock() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.externalFence.set(true);
        IOException failure = assertThrows(IOException.class, () -> fixture.drill.verify());
        assertEquals("[\"unrelated-instance\"]", fixture.fence.get());
        assertTrue(fixture.fencePresent.get());
        assertFalse(fixture.writerSession.get());
        assertFalse(fixture.events.contains("release-lock"));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("retain shardshop-kind-drill"));
    }

    @Test
    void operatorFenceAddedAfterInitialOwnershipReadIsPreserved() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.raceOnInitialFence.set(true);
        IOException failure = assertThrows(IOException.class, () -> fixture.drill.verify());
        assertEquals("[\"unrelated-instance\"]", fixture.fence.get());
        assertFalse(fixture.events.contains("fence-one"));
        assertFalse(fixture.events.contains("release-lock"));
        assertFalse(fixture.schemaPresent.get());
        assertTrue(failure.getSuppressed()[0].getMessage().contains("retain shardshop-kind-drill"));
    }

    @Test
    void operatorFenceChangedAfterRecoveryOwnershipReadIsPreserved() throws Exception {
        assertRecoveryRacePreservesOperatorFence(false);
    }

    @Test
    void restoringExplicitEmptyAnnotationPreservesAnOperatorFenceChangedAfterTheRead() throws Exception {
        assertRecoveryRacePreservesOperatorFence(true);
    }

    private void assertRecoveryRacePreservesOperatorFence(boolean originalAnnotationPresent) throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.fencePresent.set(originalAnnotationPresent);
        fixture.raceOnRestore.set(true);
        IOException failure = assertThrows(IOException.class, () -> fixture.drill.verify());
        assertEquals("injected SyncRep observation failure", failure.getMessage());
        assertEquals("[\"unrelated-instance\"]", fixture.fence.get());
        assertTrue(fixture.fencePresent.get());
        assertFalse(fixture.events.contains("unfence"));
        assertFalse(fixture.events.contains("release-lock"));
        assertFalse(fixture.writerSession.get());
        assertFalse(fixture.schemaPresent.get());
        assertTrue(failure.getSuppressed()[0].getMessage().contains("retain shardshop-kind-drill"));
    }

    @Test
    void slotConsumptionFinishesOnTheGuardedThreadBeforeFailureRecoveryRuns() throws Exception {
        Fixture fixture = new Fixture(true);
        Thread drillThread = Thread.currentThread();
        assertThrows(IOException.class, () -> fixture.drill.verify());
        assertSame(drillThread, fixture.slotMutationThread.get());
        assertFalse(fixture.schemaPresent.get());
        assertFalse(fixture.writerSession.get());
    }

    @Test
    void interruptingSlotConsumptionUnwindsBeforeRecoveryAndStopsFurtherSlotQueries() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.interruptSlotConsumption.set(true);
        try {
            assertThrows(InterruptedException.class, () -> fixture.drill.verify());
            assertEquals(0, fixture.persistedSlotQueries.get());
            assertFalse(fixture.schemaPresent.get());
            assertEquals("release-lock", fixture.events.getLast());
        } finally {
            Thread.interrupted();
        }
    }

    /** Controlled command outcomes exercise drill restoration without kubectl, Docker, SQL or network IO. */
    private final class Fixture {
        final KindLab lab = mock(KindLab.class);
        final KindTopology topology = mock(KindTopology.class);
        final KindDatabaseDrill drill = new KindDatabaseDrill(lab, topology);
        final AtomicBoolean schemaPresent = new AtomicBoolean();
        final AtomicBoolean failCreate = new AtomicBoolean();
        final AtomicBoolean externalFence = new AtomicBoolean();
        final AtomicBoolean raceOnInitialFence = new AtomicBoolean();
        final AtomicBoolean raceOnRestore = new AtomicBoolean();
        final AtomicBoolean writerSession = new AtomicBoolean();
        final AtomicReference<String> fence = new AtomicReference<>("[]");
        final AtomicBoolean fencePresent = new AtomicBoolean();
        final AtomicInteger clusterVersion = new AtomicInteger(1);
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch recovered = new CountDownLatch(1);
        final CountDownLatch writerStarted = new CountDownLatch(1);
        final AtomicInteger uidReads = new AtomicInteger();
        final AtomicReference<Thread> slotMutationThread = new AtomicReference<>();
        final AtomicBoolean interruptSlotConsumption = new AtomicBoolean();
        final AtomicInteger persistedSlotQueries = new AtomicInteger();

        Fixture(boolean failSyncRep) throws Exception {
            when(lab.snapshot()).thenReturn(snapshot);
            when(lab.shards()).thenReturn(List.of("shard-a"));
            when(lab.inventory()).thenReturn(KindLab.parse("{\"instances\":\"3\"}"));
            when(lab.kubectl(any(String[].class))).thenAnswer(invocation -> {
                String[] arguments = (String[]) invocation.getRawArguments()[0];
                String command = String.join(" ", arguments);
                if (command.startsWith("annotate") || command.startsWith("patch cluster")) mutateFence(arguments);
                if (command.startsWith("get pod")) return uidReads.incrementAndGet() == 1 ? "old-uid" : "new-uid";
                return "";
            });
            when(lab.kubectl(any(Duration.class), any(String[].class))).thenAnswer(invocation -> {
                String command = String.join(" ", (String[]) invocation.getRawArguments()[1]);
                if (command.startsWith("delete configmap")) events.add("release-lock");
                if (command.contains("delete pvc")) throw new AssertionError("Drill must preserve PVCs");
                return "";
            });
            doAnswer(invocation -> {
                KindLab.Probe probe = invocation.getArgument(2);
                // Match KindLab's asynchronous observer execution. Mutating slot retries must bypass it.
                FutureTask<Boolean> pending = new FutureTask<>(probe::check);
                Thread worker = Thread.ofVirtual().start(pending);
                try {
                    if (!pending.get(5, TimeUnit.SECONDS)) throw new IOException("Controlled observation did not converge");
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof IOException problem) throw problem;
                    if (failure.getCause() instanceof InterruptedException problem) throw problem;
                    throw new AssertionError("Controlled observation failed", failure.getCause());
                } finally {
                    pending.cancel(true);
                    worker.interrupt();
                    worker.join(Duration.ofSeconds(5));
                }
                return null;
            }).when(lab).await(anyString(), any(Duration.class), any(KindLab.Probe.class));
            when(topology.cluster("shard-a")).thenAnswer(invocation -> cluster());
            doAnswer(invocation -> {
                if (invocation.<String>getArgument(0).equals("cluster/shard-a")) events.add("restore-quorum");
                return null;
            }).when(topology).ready(anyString());
            when(topology.primary("shard-a")).thenReturn("shard-a-1");
            KindTopology.Shard state = new KindTopology.Shard("shard-a", "shard-a-1", List.of("shard-a-2", "shard-a-3"), Map.of());
            when(topology.observe()).thenReturn(Map.of("shard-a", state));
            when(topology.volumes("shard-a")).thenReturn(Map.of());
            when(topology.sqlAttempt(anyString(), startsWith("UPDATE"))).thenReturn(new KindLab.CommandResult(1, "", "ERROR: 25006"));
            when(topology.sql(anyString(), anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(1);
                if (sql.startsWith("CREATE SCHEMA")) {
                    schemaPresent.set(true);
                    if (failCreate.get()) throw new IOException("Lost creation response");
                    return "";
                }
                if (sql.contains("DROP SCHEMA")) { events.add("drop-sql"); schemaPresent.set(false); return ""; }
                if (sql.contains("pg_terminate_backend")) { events.add("stop-writer"); writerSession.set(false); recovered.countDown(); return ""; }
                if (sql.contains("FROM pg_stat_activity")) return writerSession.get() ? "1" : "0";
                if (sql.contains("pg_create_logical_replication_slot")) return sql.split("'")[1];
                if (sql.startsWith("INSERT") || sql.endsWith("RETURNING id::text")) return "1";
                if (sql.endsWith("RETURNING phase")) return "one-standby";
                if (sql.contains("pg_logical_slot_get_binary_changes")) {
                    slotMutationThread.set(Thread.currentThread());
                    if (interruptSlotConsumption.get()) Thread.currentThread().interrupt();
                    return "t";
                }
                if (sql.contains("pg_log_standby_snapshot")) return "t";
                if (sql.contains("AND synced")) { persistedSlotQueries.incrementAndGet(); return "1"; }
                if (sql.startsWith("SELECT phase")) return "initial";
                return "";
            });
            when(lab.kubectlAttempt(any(Duration.class), any(String[].class))).thenAnswer(invocation -> {
                writerSession.set(true); writerStarted.countDown();
                if (!recovered.await(5, TimeUnit.SECONDS)) throw new IOException("Controlled writer was not recovered");
                writerSession.set(false);
                return new KindLab.CommandResult(0, "committed", "");
            });
            doAnswer(invocation -> {
                String sql = invocation.getArgument(1);
                if (sql.contains("wait_event='SyncRep'") && failSyncRep) {
                    assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
                    if (externalFence.get()) fence.set("[\"unrelated-instance\"]");
                    throw new IOException("injected SyncRep observation failure");
                }
                return null;
            }).when(topology).awaitSql(anyString(), anyString(), anyString());
        }

        private com.fasterxml.jackson.databind.JsonNode cluster() throws IOException {
            var cluster = (com.fasterxml.jackson.databind.node.ObjectNode) KindLab.parse("{}");
            var metadata = cluster.putObject("metadata").put("uid", "cluster-uid")
                    .put("resourceVersion", Integer.toString(clusterVersion.get()));
            if (fencePresent.get()) metadata.putObject("annotations").put("cnpg.io/fencedInstances", fence.get());
            return cluster;
        }

        private void mutateFence(String[] arguments) throws IOException {
            // Model an operator updating the API object after the drill's GET, before its mutation.
            if (raceOnInitialFence.compareAndSet(true, false)
                    || (events.contains("fence-both") && raceOnRestore.compareAndSet(true, false))) {
                fence.set("[\"unrelated-instance\"]");
                fencePresent.set(true);
                clusterVersion.incrementAndGet();
                events.add("operator-fence-after-read");
            }
            String value;
            boolean present;
            if (arguments[0].equals("patch")) {
                var patch = KindLab.parse(arguments[arguments.length - 1]);
                var current = cluster();
                for (var operation : patch) {
                    if (operation.path("op").asText().equals("test")
                            && !current.at(operation.path("path").asText()).equals(operation.path("value"))) {
                        throw new IOException("Conditional fence patch conflict");
                    }
                }
                var mutation = patch.get(patch.size() - 1);
                String path = mutation.path("path").asText();
                if (!path.equals("/metadata/annotations") && !path.equals("/metadata/annotations/cnpg.io~1fencedInstances")) {
                    throw new IOException("Unexpected fence mutation path: " + path);
                }
                present = !mutation.path("op").asText().equals("remove");
                value = path.equals("/metadata/annotations")
                        ? mutation.path("value").path("cnpg.io/fencedInstances").asText()
                        : mutation.path("value").asText();
            } else {
                present = !arguments[3].endsWith("-");
                value = present ? arguments[3].substring(arguments[3].indexOf('=') + 1) : "";
            }
            fence.set(present ? value : "[]");
            fencePresent.set(present);
            clusterVersion.incrementAndGet();
            if (!present || value.equals("[]") || value.isEmpty()) {
                events.add("unfence");
                recovered.countDown();
            } else events.add(value.contains("shard-a-3") ? "fence-both" : "fence-one");
        }
    }
}
