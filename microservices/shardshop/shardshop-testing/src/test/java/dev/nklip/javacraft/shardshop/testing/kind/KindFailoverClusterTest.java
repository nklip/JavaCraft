package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KindFailoverClusterTest {
    @TempDir Path temporary;

    @Test
    void switchoverUsesCnpgStatusAndPreservesLineageAndExactVolumes() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        assertEquals("shard-a-2", helper.switchover("shard-a-2"));
        assertTrue(f.events.indexOf("registered") < f.events.indexOf("status patch"));
        assertEquals("Switchover in progress", f.cluster.path("status").path("phase").asText());
        verify(f.topology).endpoints("shard-a-rw", List.of("shard-a-2"));
        verify(f.topology).endpoints("shard-a-ro", List.of("shard-a-1", "shard-a-3"));
        verify(f.topology).streaming("shard-a-2", List.of("shard-a-1", "shard-a-3"));
        f.recoveries.getFirst().check();
        assertEquals("shard-a-1", f.primary());
        verify(f.topology, atLeastOnce()).volumes("shard-a");
    }

    @Test
    void missingOperatorLogicalSlotManagementIsRejectedBeforeAnyDrillMutation() throws Exception {
        Fixture f = new Fixture(temporary);
        f.cluster.withObject("/spec/replicationSlots/highAvailability").remove("synchronizeLogicalDecoding");

        IOException failure = assertThrows(IOException.class, f::helper);

        assertTrue(failure.getMessage().contains("CNPG-managed logical slot synchronization must be enabled"));
        assertEquals(List.of(), f.events);
        assertEquals(List.of(), f.recoveries);
    }

    @Test
    void switchoverLiftsOwnedStandbyFenceOnlyAfterPromotion() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        helper.fenceStandby("shard-a-3");
        helper.switchover("shard-a-2");
        assertTrue(f.events.indexOf("promoted") < f.events.indexOf("unfenced"));
        assertFalse(f.cluster.path("metadata").path("annotations").has("cnpg.io/fencedInstances"));
    }

    @Test
    void primaryFailureStopsTheOldPrimaryBeforeUnfencingAndUsesDeletePreconditions() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        helper.fenceStandby("shard-a-3");
        assertEquals("shard-a-2", helper.failPrimary());
        assertTrue(f.events.indexOf("deleted shard-a-1") < f.events.indexOf("unfenced"));
        assertTrue(f.events.indexOf("promoted") < f.events.indexOf("released shard-a-1"));
        assertEquals("shard-a-1-uid", f.deleteOptions.path("preconditions").path("uid").asText());
        assertEquals("1", f.deleteOptions.path("preconditions").path("resourceVersion").asText());
        assertEquals(30, f.deleteOptions.path("gracePeriodSeconds").asInt());
        assertEquals(List.of("foreign/finalizer"), strings(f.pods.get("shard-a-1").path("metadata").path("finalizers")));
        verify(f.topology, atLeastOnce()).sql("shard-a-1", "SELECT pg_is_in_recovery(), current_setting('transaction_read_only')");
        try (var files = Files.list(temporary)) { assertEquals(0, files.count(), "DeleteOptions temporary file is removed"); }
    }

    @Test
    void lostDeleteResponseRetainsRecoveryOfTheExactOwnedFinalizer() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        f.loseDeleteResponse = true;
        assertThrows(IOException.class, helper::failPrimary);
        assertEquals(2, f.pods.get("shard-a-1").path("metadata").path("finalizers").size());
        f.promote("shard-a-2");
        f.recoveries.getFirst().check();
        assertEquals(List.of("foreign/finalizer"), strings(f.pods.get("shard-a-1").path("metadata").path("finalizers")));
        assertEquals("shard-a-1", f.primary());
    }

    @Test
    void twoWritablePrimariesAreATerminalFailureBeforeAnyTransitionMutation() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        doReturn(new KindLab.CommandResult(0, "f|off", ""))
                .when(f.topology).sqlAttempt(anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> helper.switchover("shard-a-2"));
        assertEquals(List.of("registered"), f.events);
    }

    @Test
    void roleSampleStraddlingHandoverRechecksCandidatesBeforeRejectingSplitBrain() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        KindLab.CommandResult primary = new KindLab.CommandResult(0, "f|off", "");
        KindLab.CommandResult standby = new KindLab.CommandResult(0, "t|on", "");
        // The first pass sees the old primary before demotion and its successor
        // after promotion. An immediate second pass sees only the successor.
        doReturn(primary, standby).when(f.topology).sqlAttempt(eq("shard-a-1"), anyString());
        doReturn(primary).when(f.topology).sqlAttempt(eq("shard-a-2"), anyString());
        doReturn(standby).when(f.topology).sqlAttempt(eq("shard-a-3"), anyString());

        assertDoesNotThrow(helper::assertAtMostOneWritable);

        verify(f.topology, times(2)).sqlAttempt(eq("shard-a-1"), anyString());
        verify(f.topology, times(2)).sqlAttempt(eq("shard-a-2"), anyString());
        verify(f.topology, times(1)).sqlAttempt(eq("shard-a-3"), anyString());
        assertEquals(List.of("registered"), f.events);
    }

    @Test
    void recoveryRefusesForeignFencesAndReplacementClusters() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        f.cluster.withObject("/metadata/annotations").put("cnpg.io/fencedInstances", "[\"foreign\"]");
        assertThrows(IOException.class, helper::restoreStandbys);
        assertEquals(List.of("registered"), f.events);
        f.cluster.withObject("/metadata/annotations").remove("cnpg.io/fencedInstances");
        f.cluster.withObject("/metadata").put("uid", "replacement");
        assertThrows(IOException.class, helper::restoreStandbys);
        assertEquals(List.of("registered"), f.events);
    }

    @Test
    void compareAndSwapRejectsAnOperatorRaceBeforeStatusMutation() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        f.racePatch = true;
        assertThrows(IOException.class, () -> helper.switchover("shard-a-2"));
        assertEquals("shard-a-1", f.primary());
        assertEquals("shard-a-1", f.cluster.path("status").path("targetPrimary").asText());
    }

    @Test
    void fenceRetriesOnlyObservedConcurrentVersionChangesAndAcceptsALostSuccessfulResponse() throws Exception {
        Fixture concurrent = new Fixture(temporary);
        KindFailoverCluster first = concurrent.helper();
        concurrent.fenceConflicts = 1;
        first.fenceStandby("shard-a-2");
        assertEquals(2, concurrent.fenceAttempts);
        assertEquals("[\"shard-a-2\"]", concurrent.cluster.path("metadata").path("annotations").path("cnpg.io/fencedInstances").asText());

        Fixture lost = new Fixture(temporary);
        KindFailoverCluster second = lost.helper();
        lost.loseFenceResponse = true;
        second.fenceStandby("shard-a-2");
        assertEquals(1, lost.fenceAttempts, "Observed target makes an uncertain successful patch idempotent");
    }

    @Test
    void fenceDoesNotRetryGenericFailuresOrForeignStateAndBoundsConcurrentConflicts() throws Exception {
        for (String fault : List.of("unchanged", "foreign", "replacement", "exhaustion")) {
            Fixture f = new Fixture(temporary);
            KindFailoverCluster helper = f.helper();
            f.fenceFailure = fault;
            if (fault.equals("exhaustion")) f.fenceConflicts = 3;

            assertThrows(IOException.class, () -> helper.fenceStandby("shard-a-2"), fault);

            assertEquals(fault.equals("exhaustion") ? 3 : 1, f.fenceAttempts, fault);
        }
    }

    @Test
    void fenceSemanticComparisonIgnoresStatusChurnAndPreservesOtherAnnotations() throws Exception {
        Fixture f = new Fixture(temporary);
        f.cluster.withObject("/metadata/annotations").put("operator/kept", "original");
        KindFailoverCluster helper = f.helper();
        f.fenceRace = "status";

        helper.fenceStandby("shard-a-2");

        assertEquals("[\"shard-a-2\"]", f.cluster.path("metadata").path("annotations").path("cnpg.io/fencedInstances").asText(),
                "Fencing must converge despite status updates racing every patch attempt");
        assertEquals("original", f.cluster.path("metadata").path("annotations").path("operator/kept").asText());
        f.fenceRace = "annotation";
        helper.restoreStandbys();
        assertEquals("original", f.cluster.path("metadata").path("annotations").path("operator/kept").asText());
        assertEquals("concurrent", f.cluster.path("metadata").path("annotations").path("operator/new").asText());
        assertFalse(f.cluster.path("metadata").path("annotations").has("cnpg.io/fencedInstances"));
    }

    @Test
    void absentFenceComparisonRetriesConcurrentAnnotationMapUpdatesWithoutLosingThem() throws Exception {
        Fixture f = new Fixture(temporary);
        f.cluster.withObject("/metadata/annotations").put("operator/kept", "original");
        KindFailoverCluster helper = f.helper();
        f.fenceRace = "annotation";

        helper.fenceStandby("shard-a-2");

        assertEquals(2, f.fenceAttempts, "An absent key requires comparing the observed annotations map");
        assertEquals("original", f.cluster.path("metadata").path("annotations").path("operator/kept").asText());
        assertEquals("concurrent", f.cluster.path("metadata").path("annotations").path("operator/new").asText());
        assertEquals("[\"shard-a-2\"]", f.cluster.path("metadata").path("annotations").path("cnpg.io/fencedInstances").asText());
    }

    @Test
    void fenceSemanticComparisonRejectsForeignFenceAndReplacementUidBetweenReadAndPatch() throws Exception {
        for (String race : List.of("foreign", "replacement")) {
            Fixture f = new Fixture(temporary);
            KindFailoverCluster helper = f.helper();
            helper.fenceStandby("shard-a-2");
            f.fenceRace = race;

            assertThrows(IOException.class, helper::restoreStandbys, race);

            assertEquals(2, f.fenceAttempts, "The concurrent foreign state is not overwritten or retried");
            if (race.equals("foreign")) assertEquals("[\"foreign\"]",
                    f.cluster.path("metadata").path("annotations").path("cnpg.io/fencedInstances").asText());
            else assertEquals("replacement", f.cluster.path("metadata").path("uid").asText());
        }
    }

    @Test
    void unsafeObservationRejectsNominationAndPromotionAndIncludesDetectionWindow() throws Exception {
        ObjectNode cluster = (ObjectNode) KindLab.parse("{\"status\":{\"currentPrimary\":\"shard-a-1\",\"targetPrimary\":\"shard-a-1\"}}");
        KindFailoverCluster.assertUnconfirmed(cluster, "shard-a-1", "shard-a-2");
        cluster.withObject("/status").put("targetPrimary", "pending");
        KindFailoverCluster.assertUnconfirmed(cluster, "shard-a-1", "shard-a-2");
        cluster.withObject("/status").put("targetPrimary", "shard-a-2");
        assertThrows(IOException.class, () -> KindFailoverCluster.assertUnconfirmed(cluster, "shard-a-1", "shard-a-2"));
        cluster.withObject("/status").put("currentPrimary", "shard-a-2");
        assertThrows(IOException.class, () -> KindFailoverCluster.assertUnconfirmed(cluster, "shard-a-1", "shard-a-2"));
        assertEquals(Duration.ofSeconds(180), KindFailoverCluster.noPromotionWindow(cluster));
        cluster.withObject("/spec").put("failoverDelay", 30);
        assertEquals(Duration.ofSeconds(210), KindFailoverCluster.noPromotionWindow(cluster));
        cluster.withObject("/spec").put("failoverDelay", 61);
        assertThrows(IOException.class, () -> KindFailoverCluster.noPromotionWindow(cluster));
    }

    @Test
    void anUnexpectedPvcReplacementFailsHealthyAcceptance() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        when(f.topology.volumes("shard-a")).thenReturn(Map.of("data", new KindTopology.Volume("new-uid", "new-pv", "Bound")));
        assertThrows(IOException.class, helper::assertHealthy);
    }

    @Test
    void recoverySwitchoverCannotMutateStatusWithMissingOrBehindLogicalSlots() throws Exception {
        for (String target : List.of("[]", "[" + slot("0/FF", true, "pgoutput") + "]")) {
            Fixture f = new Fixture(temporary);
            KindFailoverCluster helper = f.helper();
            doReturn("[" + slot("0/100", false, "pgoutput") + "]")
                    .when(f.topology).sql("shard-a-1", KindFailoverCluster.FAILOVER_SLOTS_SQL);
            doReturn(target).when(f.topology).sql("shard-a-2", KindFailoverCluster.LOGICAL_SLOTS_SQL);
            doAnswer(invocation -> {
                assertFalse(invocation.<KindLab.Probe>getArgument(2).check());
                throw new IOException("Bounded wait expired before logical slots were safe");
            }).when(f.lab).await(startsWith("synchronized failover logical slots"), any(), any());

            assertThrows(IOException.class, () -> helper.switchover("shard-a-2"));

            assertEquals(List.of("registered"), f.events, "Unsafe recovery candidate never receives a status mutation");
            assertEquals("shard-a-1", f.primary());
        }
    }

    @Test
    void missingAllSourceLogicalSlotsCannotAuthorizeRecoverySwitchover() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        doReturn("[]").when(f.topology).sql("shard-a-1", KindFailoverCluster.FAILOVER_SLOTS_SQL);

        IOException failure = assertThrows(IOException.class, () -> helper.switchover("shard-a-2"));

        assertTrue(failure.getMessage().contains("requires a permanent failover logical slot"));
        assertEquals(List.of("registered"), f.events);
        verify(f.lab, never()).await(startsWith("synchronized failover logical slots"), any(), any());
    }

    @Test
    void synchronizedSlotsRequireIdentityPersistenceValidityAndAtLeastCapturedConfirmedPosition() throws Exception {
        JsonNode required = KindLab.parse("[" + slot("1/0", false, "pgoutput") + "]");
        assertFalse(KindFailoverCluster.synchronizedSlots(required, KindLab.parse("[]")));
        assertFalse(KindFailoverCluster.synchronizedSlots(required, KindLab.parse("[" + slot("0/FFFFFFFF", true, "pgoutput") + "]")));
        assertFalse(KindFailoverCluster.synchronizedSlots(required, KindLab.parse("[" + slot("1/0", false, "pgoutput") + "]")));
        assertFalse(KindFailoverCluster.synchronizedSlots(required, KindLab.parse("[" + slot("1/0", true, "other") + "]")));
        ObjectNode target = (ObjectNode) KindLab.parse(slot("1/1", true, "pgoutput"));
        target.put("temporary", true);
        assertFalse(KindFailoverCluster.synchronizedSlots(required, JsonNodeFactory.instance.arrayNode().add(target)));
        target.put("temporary", false).put("invalidation_reason", "wal_removed");
        assertFalse(KindFailoverCluster.synchronizedSlots(required, JsonNodeFactory.instance.arrayNode().add(target)));
        target.putNull("invalidation_reason");
        assertTrue(KindFailoverCluster.synchronizedSlots(required, JsonNodeFactory.instance.arrayNode().add(target)));
    }

    private static String slot(String confirmed, boolean synced, String plugin) {
        return "{\"slot_name\":\"shardshop_order\",\"database\":\"shardshop\",\"plugin\":\"" + plugin
                + "\",\"active\":false,\"failover\":true,\"temporary\":false,\"synced\":" + synced
                + ",\"invalidation_reason\":null,\"confirmed_flush_lsn\":\"" + confirmed + "\"}";
    }

    @Test
    void demotedPrimaryRetiresOnlyItsCapturedInactiveSlotThenAwaitsNativeSynchronization() throws Exception {
        Fixture f = new Fixture(temporary);
        f.orphanOnDemotion = true;
        KindFailoverCluster helper = f.helper();

        helper.switchover("shard-a-2");

        assertEquals(List.of("shard-a-1"), f.retired);
        assertTrue(f.retirementStatement.contains("WHERE pg_is_in_recovery()"));
        assertTrue(f.retirementStatement.contains("NOT synced AND NOT active AND NOT temporary AND invalidation_reason IS NULL"));
        assertTrue(f.retirementStatement.contains("database='shardshop' AND plugin='pgoutput'"));
        assertTrue(f.retirementStatement.contains("confirmed_flush_lsn='1/0'::pg_lsn"));
        verify(f.lab).await(eq("native synchronized logical slots on demoted primary shard-a-1"),
                eq(KindTopology.CONVERGENCE), any());
    }

    @Test
    void demotedSlotRetirementRefusesActiveChangedForeignAndMissingAuthoritativeCopies() throws Exception {
        for (String fault : List.of("active", "identity", "foreign", "source", "primary")) {
            Fixture f = new Fixture(temporary);
            f.orphanOnDemotion = true;
            f.demotionFault = fault;
            KindFailoverCluster helper = f.helper();
            if (fault.equals("source")) doReturn("[]").when(f.topology).sql("shard-a-2", KindFailoverCluster.FAILOVER_SLOTS_SQL);

            assertThrows(IOException.class, () -> helper.switchover("shard-a-2"), fault);

            assertEquals(List.of(), f.retired, "No retirement is authorized for " + fault);
        }
    }

    @Test
    void ownedSlotWitnessRegistersRecoveryBeforeCreationAndCleansOnlyItsNames() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();

        helper.prepareSlotWitness();

        assertNotNull(f.ownedProbe);
        String owned = f.ownedProbe;
        assertEquals(2, f.recoveries.size());
        f.recoveries.getLast().check();
        assertFalse(f.witnessCreated);
        for (String pod : List.of("shard-a-1", "shard-a-2", "shard-a-3")) verify(f.topology).awaitSql(pod,
                "SELECT count(*) FROM pg_replication_slots WHERE slot_name='" + owned + "'", "0");
        verify(f.topology).sql(eq("shard-a-1"), contains("DROP PUBLICATION IF EXISTS " + owned));
    }

    @Test
    void slotRetirementRejectsTemporaryInvalidatedSynchronizedOrOutOfLineagePositions() throws Exception {
        JsonNode captured = KindLab.parse(slot("1/0", false, "pgoutput"));
        JsonNode source = KindLab.parse(slot("1/2", true, "pgoutput"));
        for (String fault : List.of("temporary", "invalidated", "synced", "behind", "ahead")) {
            ObjectNode local = (ObjectNode) KindLab.parse(slot("1/1", false, "pgoutput"));
            switch (fault) {
                case "temporary" -> local.put("temporary", true);
                case "invalidated" -> local.put("invalidation_reason", "wal_removed");
                case "synced" -> local.put("synced", true);
                case "behind" -> local.put("confirmed_flush_lsn", "0/FFFFFFFF");
                case "ahead" -> local.put("confirmed_flush_lsn", "1/3");
                default -> fail("Unexpected fixture");
            }
            assertThrows(IOException.class, () -> KindFailoverCluster.retirementSql(captured, source, local), fault);
        }
    }

    @Test
    void recreatedTemporaryOwnedSlotIsPrimedWithoutConsumingTheBusinessSlot() throws Exception {
        Fixture f = new Fixture(temporary);
        f.orphanOnDemotion = true;
        f.temporaryProbeAfterRetirement = true;
        KindFailoverCluster helper = f.helper();
        helper.prepareSlotWitness();

        helper.switchover("shard-a-2");

        assertEquals(1, f.recreatedProbePrimes);
        verify(f.topology, never()).sql(anyString(), contains("pg_logical_slot_get_binary_changes('shardshop_order'"));
        assertTrue(f.ownedDecodeCalls >= 2);
    }

    @Test
    void ownedDecodeRequiresWarningFreeSuccessAfterOnlyTheDocumentedStandbyWaitWarning() throws Exception {
        Fixture f = new Fixture(temporary);
        f.ownedDecodeResults.add(new KindLab.CommandResult(0, "1", "WARNING:  55000: replication slot \"_cnpg_shard_a_2\" specified in parameter \"synchronized_standby_slots\" does not have active_pid\n"));
        f.ownedDecodeResults.add(new KindLab.CommandResult(0, "1", ""));

        f.helper().prepareSlotWitness();

        assertEquals(2, f.ownedDecodeCalls, "A warning-bearing success cannot satisfy final owned-probe decoding");
    }

    @Test
    void ownedDecodeWaitsForBothPhysicalStandbysAndDoesNotRetryOtherWarningsOrErrors() throws Exception {
        Fixture waiting = new Fixture(temporary);
        waiting.physicalReadiness.addAll(List.of(false, true));
        waiting.helper().prepareSlotWitness();
        assertTrue(waiting.physicalChecks >= 2);
        assertEquals(1, waiting.ownedDecodeCalls);
        String documented = "WARNING:  55000: replication slot \"_cnpg_shard_a_2\" specified in parameter \"synchronized_standby_slots\" does not have active_pid\n";
        for (KindLab.CommandResult result : List.of(
                new KindLab.CommandResult(1, "", documented + "ERROR:  57014: canceling statement\n"),
                new KindLab.CommandResult(0, "1", "WARNING:  55000: another prerequisite failed\n"),
                new KindLab.CommandResult(0, "1", documented + "WARNING:  01000: unrelated warning\n"))) {
            Fixture f = new Fixture(temporary);
            f.ownedDecodeResults.add(result);
            assertThrows(IOException.class, () -> f.helper().prepareSlotWitness());
            assertEquals(1, f.ownedDecodeCalls, "Unknown warnings and nonzero exits are terminal");
        }
    }

    @Test
    void ownedDecodeWarningRecoveryRequiresACleanAttemptWithinThreeTries() throws Exception {
        Fixture f = new Fixture(temporary);
        f.ownedDecodeResults.add(new KindLab.CommandResult(0, "1", "WARNING:  55000: replication slot \"_cnpg_shard_a_2\" specified in parameter \"synchronized_standby_slots\" does not have active_pid\n"));
        assertThrows(IOException.class, () -> f.helper().prepareSlotWitness());
        assertEquals(3, f.ownedDecodeCalls);
    }

    @Test
    void lostOwnedSlotCreationResponseStillCleansItsPartialSetup() throws Exception {
        Fixture f = new Fixture(temporary);
        f.loseCreateResponse = true;
        KindFailoverCluster helper = f.helper();

        assertThrows(IOException.class, helper::prepareSlotWitness);
        assertTrue(f.witnessCreated);
        f.recoveries.getLast().check();

        assertFalse(f.witnessCreated);
        verify(f.topology).sql(anyString(), contains("DROP SCHEMA IF EXISTS " + f.ownedProbe));
    }

    @Test
    void unsafeDrillRequiresAnActuallyMissingAcknowledgedCommitAndRecoversBothLosses() throws Exception {
        Fixture f = new Fixture(temporary);
        KindFailoverCluster helper = f.helper();
        IOException failure = assertThrows(IOException.class, helper::unsafeSurvivor);
        assertTrue(failure.getMessage().contains("Survivor actually lacks an acknowledged commit"));
        assertTrue(f.events.contains("deleted shard-a-3"));
        assertTrue(f.events.contains("deleted shard-a-1"));
        assertTrue(f.events.indexOf("deleted shard-a-3") < f.events.indexOf("deleted shard-a-1"));
        assertEquals(3, f.recoveries.size(), "Witness cleanup and fault recovery exist before SQL/fault mutations");
        for (KindLab.Probe recovery : f.recoveries.reversed()) recovery.check();
        assertEquals(List.of("foreign/finalizer"), strings(f.pods.get("shard-a-1").path("metadata").path("finalizers")));
        assertEquals(List.of("foreign/finalizer"), strings(f.pods.get("shard-a-3").path("metadata").path("finalizers")));
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static final class Fixture {
        final KindLab lab = mock();
        final KindTopology topology = mock();
        final KindDrillGuard guard = mock();
        final KindTopology.Shard state = new KindTopology.Shard("shard-a", "shard-a-1", List.of("shard-a-2", "shard-a-3"),
                Map.of("data", new KindTopology.Volume("pvc-uid", "pv-name", "Bound")));
        final List<KindLab.Probe> recoveries = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final Map<String, ObjectNode> pods = new HashMap<>();
        final ObjectNode cluster;
        JsonNode deleteOptions;
        boolean loseDeleteResponse;
        boolean racePatch;
        boolean orphanOnDemotion;
        String demotionFault = "";
        final Map<String, String> slotObservations = new HashMap<>();
        final List<String> retired = new ArrayList<>();
        String retirementStatement;
        String ownedProbe;
        boolean witnessCreated;
        boolean loseCreateResponse;
        boolean temporaryProbeAfterRetirement;
        String waitingProbePod;
        int recreatedProbePrimes;
        int fenceConflicts;
        int fenceAttempts;
        String fenceFailure = "";
        boolean loseFenceResponse;
        String fenceRace = "";
        final List<KindLab.CommandResult> ownedDecodeResults = new ArrayList<>();
        int ownedDecodeCalls;
        final List<Boolean> physicalReadiness = new ArrayList<>();
        int physicalChecks;

        Fixture(Path temporary) throws Exception {
            cluster = (ObjectNode) KindLab.parse("{\"metadata\":{\"uid\":\"cluster-uid\",\"resourceVersion\":\"1\"},"
                    + "\"spec\":{\"postgresql\":{\"synchronous\":{\"failoverQuorum\":true}},"
                    + "\"replicationSlots\":{\"highAvailability\":{\"synchronizeLogicalDecoding\":true}}},"
                    + "\"status\":{\"currentPrimary\":\"shard-a-1\",\"targetPrimary\":\"shard-a-1\"}}");
            for (int i = 1; i <= 3; i++) {
                String name = "shard-a-" + i;
                ObjectNode object = JsonNodeFactory.instance.objectNode();
                object.putObject("metadata").put("uid", name + "-uid").put("resourceVersion", "1")
                        .putArray("finalizers").add("foreign/finalizer");
                pods.put(name, object);
            }
            when(lab.snapshot()).thenReturn(temporary);
            when(topology.cluster("shard-a")).thenAnswer(invocation -> cluster.deepCopy());
            when(topology.primary("shard-a")).thenAnswer(invocation -> primary());
            when(topology.volumes("shard-a")).thenReturn(state.volumes());
            when(topology.sql(anyString(), anyString())).thenAnswer(invocation -> {
                String statement = invocation.getArgument(1);
                if (statement.contains("system_identifier")) return "123456789";
                if (statement.startsWith("SELECT count(*)=2 AND coalesce(bool_and(s.active")) {
                    physicalChecks++;
                    boolean ready = physicalReadiness.isEmpty() || (physicalReadiness.size() == 1
                            ? physicalReadiness.getFirst() : physicalReadiness.removeFirst());
                    if (!ready) assertEquals(0, ownedDecodeCalls, "No decode before both physical standbys are ready");
                    return ready ? "t" : "f";
                }
                if (statement.contains("pg_logical_slot_get_binary_changes(")) return KindTopology.successfulSql(ownedDecodeResult());
                if (statement.startsWith("SELECT slot_name FROM pg_create_logical_replication_slot(")) {
                    assertTrue(recoveries.size() >= 2, "Witness recovery exists before creation");
                    ownedProbe = statement.split("'")[1];
                    witnessCreated = true;
                    if (loseCreateResponse) { loseCreateResponse = false; throw new IOException("Lost owned slot creation response"); }
                    return ownedProbe;
                }
                if (statement.startsWith("SELECT pg_drop_replication_slot(slot_name)")) {
                    assertTrue(statement.contains("slot_name='" + ownedProbe + "'"));
                    witnessCreated = false;
                    return "";
                }
                if (statement.equals(KindFailoverCluster.FAILOVER_SLOTS_SQL)) return slotRows(false);
                if (statement.equals(KindFailoverCluster.LOGICAL_SLOTS_SQL)) return slotObservations.getOrDefault(
                        invocation.getArgument(0), slotRows(true));
                if (statement.startsWith("SELECT slot_name, pg_drop_replication_slot")) {
                    String pod = invocation.getArgument(0);
                    retired.add(pod);
                    retirementStatement = statement;
                    var matcher = java.util.regex.Pattern.compile("slot_name='([^']+)'").matcher(statement);
                    assertTrue(matcher.find());
                    String name = matcher.group(1);
                    ArrayNode remaining = (ArrayNode) KindLab.parse(slotObservations.get(pod));
                    for (JsonNode row : remaining) if (row.path("slot_name").asText().equals(name)) {
                        ((ObjectNode) row).put("synced", true);
                        if (name.equals(ownedProbe) && temporaryProbeAfterRetirement) {
                            ((ObjectNode) row).put("temporary", true).put("active", true).putNull("confirmed_flush_lsn");
                            waitingProbePod = pod;
                        }
                    }
                    slotObservations.put(pod, remaining.toString());
                    return name + "|";
                }
                if (waitingProbePod != null && statement.startsWith("INSERT INTO " + ownedProbe + ".rows")) {
                    slotObservations.remove(waitingProbePod);
                    waitingProbePod = null;
                    recreatedProbePrimes++;
                }
                if (statement.contains(".witness VALUES") || statement.startsWith("SELECT count(*) FROM failover_probe_")) return "1";
                if (statement.contains("transaction_read_only") && demotionFault.equals("primary")
                        && invocation.getArgument(0).equals("shard-a-1") && !primary().equals("shard-a-1")) return "f|off";
                return role(invocation.getArgument(0));
            });
            when(topology.sqlAttempt(anyString(), anyString())).thenAnswer(invocation -> {
                String pod = invocation.getArgument(0);
                if (pods.get(pod).path("metadata").has("deletionTimestamp")) return new KindLab.CommandResult(1, "", "unavailable");
                return new KindLab.CommandResult(0, role(pod), "");
            });
            doAnswer(invocation -> {
                recoveries.add(invocation.getArgument(1));
                events.add("registered");
                return null;
            }).when(guard).onClose(anyString(), any());
            doAnswer(invocation -> {
                if (invocation.<String>getArgument(0).contains("promotion")) {
                    String target = cluster.path("status").path("targetPrimary").asText();
                    promote(target.equals(primary()) ? "shard-a-2" : target);
                }
                assertTrue(invocation.<KindLab.Probe>getArgument(2).check());
                return null;
            }).when(lab).await(anyString(), any(), any());
            when(lab.kubectl(any(String[].class))).thenAnswer(invocation -> command((String[]) invocation.getRawArguments()[0]));
            when(lab.kubectl(any(Duration.class), any(String[].class)))
                    .thenAnswer(invocation -> command((String[]) invocation.getRawArguments()[1]));
            when(lab.kubectlAttempt(any(String[].class))).thenAnswer(invocation -> {
                String[] args = (String[]) invocation.getRawArguments()[0];
                assertTrue(args[args.length - 1].contains("pg_logical_slot_get_binary_changes('" + ownedProbe + "'"));
                return ownedDecodeResult();
            });
            when(lab.kubectlAttempt(any(Duration.class), any(String[].class))).thenAnswer(invocation -> {
                String[] args = (String[]) invocation.getRawArguments()[1];
                assertTrue(args[args.length - 1].contains("pg_logical_slot_get_binary_changes('" + ownedProbe + "'"));
                assertTrue(physicalChecks > 0, "Every owned decode follows the physical standby barrier");
                assertTrue(List.of(args).contains("VERBOSITY=verbose"));
                return ownedDecodeResult();
            });
        }

        KindFailoverCluster helper() throws Exception { return new KindFailoverCluster(lab, topology, guard, "shard-a", state); }
        String primary() { return cluster.path("status").path("currentPrimary").asText(); }
        KindLab.CommandResult ownedDecodeResult() {
            ownedDecodeCalls++;
            return ownedDecodeResults.isEmpty() ? new KindLab.CommandResult(0, "1", "")
                    : ownedDecodeResults.size() == 1 ? ownedDecodeResults.getFirst() : ownedDecodeResults.removeFirst();
        }
        String role(String pod) { return pod.equals(primary()) ? "f|off" : "t|on"; }
        String slotRows(boolean synced) {
            String business = slot("1/0", synced, "pgoutput");
            return "[" + business + (witnessCreated ? "," + business.replace("shardshop_order", ownedProbe) : "") + "]";
        }
        void promote(String target) throws IOException {
            if (orphanOnDemotion && !target.equals(primary())) {
                ObjectNode old = (ObjectNode) KindLab.parse(slot("1/0", false, "pgoutput"));
                if (demotionFault.equals("active")) old.put("active", true);
                if (demotionFault.equals("identity")) old.put("database", "foreign_database");
                ArrayNode slots = JsonNodeFactory.instance.arrayNode().add(old);
                if (witnessCreated) slots.add(old.deepCopy().put("slot_name", ownedProbe));
                if (demotionFault.equals("foreign")) slots.add(old.deepCopy().put("slot_name", "foreign_slot"));
                slotObservations.put(primary(), slots.toString());
            }
            cluster.withObject("/status").put("currentPrimary", target).put("targetPrimary", target);
            events.add("promoted");
        }

        String command(String[] arguments) throws Exception {
            List<String> args = List.of(arguments);
            if (args.contains("delete")) {
                String name = args.get(args.indexOf("--raw") + 1).substring("/api/v1/namespaces/shardshop/pods/".length());
                deleteOptions = KindLab.parse(Files.readString(Path.of(args.get(args.indexOf("-f") + 1))));
                ObjectNode object = pods.get(name);
                assertEquals(object.path("metadata").path("uid").asText(), deleteOptions.path("preconditions").path("uid").asText());
                object.withObject("/metadata").put("deletionTimestamp", "2026-10-26T00:00:00Z");
                object.withObject("/status").putArray("containerStatuses").addObject().put("name", "postgres")
                        .putObject("state").putObject("terminated").put("exitCode", 0);
                events.add("deleted " + name);
                if (loseDeleteResponse) { loseDeleteResponse = false; throw new IOException("Lost DELETE response"); }
                return "";
            }
            if (args.getFirst().equals("get") && args.get(1).equals("failoverquorum")) {
                return "{\"status\":{\"primary\":\"shard-a-1\",\"method\":\"any\",\"standbyNumber\":1,"
                        + "\"standbyNames\":[\"shard-a-2\",\"shard-a-3\"]}}";
            }
            if (args.getFirst().equals("get") && args.get(1).equals("cluster")) return cluster.toString();
            if (args.getFirst().equals("get")) return pods.get(args.get(2)).toString();
            if (!args.getFirst().equals("patch")) return "";
            boolean fencePatch = args.get(1).equals("cluster") && !args.contains("--subresource=status");
            if (fencePatch) {
                fenceAttempts++;
                if (!fenceRace.isEmpty()) {
                    cluster.withObject("/metadata").put("resourceVersion", "concurrent-" + fenceAttempts);
                    switch (fenceRace) {
                        case "status" -> cluster.withObject("/status").put("phase", "operator reconciliation");
                        case "annotation" -> cluster.withObject("/metadata/annotations").put("operator/new", "concurrent");
                        case "foreign" -> cluster.withObject("/metadata/annotations").put("cnpg.io/fencedInstances", "[\"foreign\"]");
                        case "replacement" -> cluster.withObject("/metadata").put("uid", "replacement");
                        default -> fail("Unexpected fence race fixture");
                    }
                    if (!fenceRace.equals("status")) fenceRace = "";
                }
                if (fenceConflicts-- > 0) {
                    cluster.withObject("/metadata").put("resourceVersion", Integer.toString(fenceAttempts + 1));
                    throw new IOException("Simulated concurrent update");
                }
                if (fenceFailure.equals("unchanged")) throw new IOException("Simulated command failure");
                if (fenceFailure.equals("foreign")) {
                    cluster.withObject("/metadata/annotations").put("cnpg.io/fencedInstances", "[\"foreign\"]");
                    throw new IOException("Simulated foreign fence");
                }
                if (fenceFailure.equals("replacement")) {
                    cluster.withObject("/metadata").put("uid", "replacement");
                    throw new IOException("Simulated replacement cluster");
                }
            }
            ObjectNode object = args.get(1).equals("cluster") ? cluster : pods.get(args.get(2));
            JsonNode patch = KindLab.parse(args.getLast());
            if (racePatch) { racePatch = false; object.withObject("/metadata").put("resourceVersion", "operator-update"); }
            for (JsonNode operation : patch) {
                String path = operation.path("path").asText();
                String op = operation.path("op").asText();
                if (op.equals("test")) {
                    if (!object.at(path).equals(operation.path("value"))) throw new IOException("Conditional patch conflict");
                } else {
                    int last = path.lastIndexOf('/');
                    JsonNode parent = object.at(path.substring(0, last));
                    String key = path.substring(last + 1).replace("~1", "/").replace("~0", "~");
                    if (parent instanceof ArrayNode array) {
                        array.remove(Integer.parseInt(key));
                        events.add("released " + args.get(2));
                        object.withObject("/metadata").remove("deletionTimestamp");
                    } else if (op.equals("remove")) {
                        ((ObjectNode) parent).remove(key);
                        events.add("unfenced");
                    } else ((ObjectNode) parent).set(key, operation.path("value").deepCopy());
                }
            }
            if (args.contains("--subresource=status")) events.add("status patch");
            if (fencePatch && loseFenceResponse) { loseFenceResponse = false; throw new IOException("Lost successful fence response"); }
            return "";
        }
    }

}
