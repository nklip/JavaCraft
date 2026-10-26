package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class KindFailoverWitnessTest {
    private static final String IDENTITY = "shardshop_order_shard_a";
    private static final String KEY = "[\"" + IDENTITY + "\",{\"server\":\"" + IDENTITY + "\"}]";
    private static final String HISTORY = "[{\"reader_name\":\"" + IDENTITY + "\",\"initialized_at\":\"2026-10-26T00:00:00Z\"}]";
    private static final String HASH = "a".repeat(64);

    @Test void write503RetriesTheSameFrozenAllocationAndAcceptsItsCreatedResponse() throws Exception {
        HttpRequest request = allocationRequest();
        KindFailoverWitness.WriteOrder send = mock();
        ObjectNode result = Json.object("{\"orderId\":\"123\"}");
        when(send.send(same(request))).thenReturn(new KindFailoverWitness.OrderRead(503, Json.newObject()),
                new KindFailoverWitness.OrderRead(201, result));
        assertEquals(result, KindFailoverWitness.awaitWrite(request, 201, send, new FakeWriteTime()));
        verify(send, times(2)).send(same(request));
    }

    @Test void unknownCommitReplaysTheSameAllocationKeyAndBodyWithoutAllocatingANewOrdinal() throws Exception {
        HttpRequest request = allocationRequest();
        KindFailoverWitness.WriteOrder send = mock();
        ObjectNode result = Json.object("{\"orderId\":\"123\"}");
        when(send.send(same(request))).thenThrow(new java.io.EOFException("Response lost after commit"))
                .thenReturn(new KindFailoverWitness.OrderRead(200, result));
        assertEquals(result, KindFailoverWitness.awaitWrite(request, 201, send, new FakeWriteTime()));
        verify(send, times(2)).send(same(request));
    }

    @Test void writeErrorsAndAnUnsolicitedReplayAreTerminal() throws Exception {
        for (int status : List.of(403, 409, 200, 500)) {
            HttpRequest request = allocationRequest();
            KindFailoverWitness.WriteOrder send = mock();
            when(send.send(request)).thenReturn(new KindFailoverWitness.OrderRead(status, Json.newObject()));
            assertThrows(IOException.class, () -> KindFailoverWitness.awaitWrite(request, 201, send, new FakeWriteTime()));
            verify(send, times(1)).send(request);
        }
    }

    @Test void aPutRetriesOnlyTheSameOrderIdAndPayloadAfterAnUncertainCommit() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1/api/v1/buyers/1/orders/123"))
                .timeout(Duration.ofSeconds(10)).PUT(HttpRequest.BodyPublishers.ofString("{\"currency\":\"USD\",\"items\":[]}")).build();
        KindFailoverWitness.WriteOrder send = mock();
        when(send.send(same(request))).thenReturn(new KindFailoverWitness.OrderRead(503, Json.newObject()),
                new KindFailoverWitness.OrderRead(200, Json.newObject()));
        KindFailoverWitness.awaitWrite(request, 202, send, new FakeWriteTime());
        verify(send, times(2)).send(same(request));
    }

    @Test void writeRecoveryExhaustionIsBoundedWithoutWaitingInTheTest() throws Exception {
        HttpRequest request = allocationRequest();
        KindFailoverWitness.WriteOrder send = mock();
        when(send.send(request)).thenReturn(new KindFailoverWitness.OrderRead(503, Json.newObject()));
        FakeWriteTime time = new FakeWriteTime();
        time.advancePerSleep = Duration.ofSeconds(180).toNanos();
        assertThrows(IOException.class, () -> KindFailoverWitness.awaitWrite(request, 201, send, time));
        verify(send, times(1)).send(request);
        assertEquals(Duration.ofSeconds(180).toNanos(), time.now());
    }

    private static HttpRequest allocationRequest() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1/api/v1/buyers/1/order-allocations"))
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString("{\"runName\":\"fixed-run\",\"requestOrdinal\":\"7\"}")).build();
    }

    private static final class FakeWriteTime implements KindFailoverWitness.WriteTime {
        private long elapsed;
        private long advancePerSleep;
        public long now() { return elapsed; }
        public void sleep(long nanos) { elapsed += advancePerSleep == 0 ? nanos : advancePerSleep; }
    }

    @Test void promotedHttpReadRetries503WithinTheRecoveryBoundAndRequiresConfirmed200() throws Exception {
        KindLab lab = mock(KindLab.class, CALLS_REAL_METHODS);
        KindFailoverWitness.ReadOrder read = mock();
        when(read.get()).thenReturn(new KindFailoverWitness.OrderRead(503, Json.newObject()),
                new KindFailoverWitness.OrderRead(200, Json.object("{\"status\":\"CONFIRMED\"}")));

        KindFailoverWitness.awaitHttpConfirmed(lab, read);

        verify(read, times(2)).get();
        verify(lab).await(anyString(), eq(Duration.ofSeconds(180)), any(KindLab.Probe.class));
    }

    @Test void unexpectedPermanentHttpErrorsFailImmediatelyAndNeverBecomeSuccessfulRecovery() throws Exception {
        KindLab lab = mock(KindLab.class, CALLS_REAL_METHODS);
        KindFailoverWitness.ReadOrder read = mock();
        when(read.get()).thenReturn(new KindFailoverWitness.OrderRead(403, Json.newObject()),
                new KindFailoverWitness.OrderRead(200, Json.object("{\"status\":\"CONFIRMED\"}")));

        IOException failure = assertThrows(IOException.class, () -> KindFailoverWitness.awaitHttpConfirmed(lab, read));

        assertTrue(failure.getMessage().contains("403"));
        verify(read, times(1)).get();
    }

    @Test void anHttp200WithPendingStatusDoesNotSatisfyThePermanentConfirmationCheck() throws Exception {
        KindLab lab = mock(KindLab.class, CALLS_REAL_METHODS);
        KindFailoverWitness.ReadOrder read = mock();
        when(read.get()).thenReturn(new KindFailoverWitness.OrderRead(200, Json.object("{\"status\":\"PENDING_LEDGER\"}")),
                new KindFailoverWitness.OrderRead(200, Json.object("{\"status\":\"CONFIRMED\"}")));

        KindFailoverWitness.awaitHttpConfirmed(lab, read);

        verify(read, times(2)).get();
    }

    @Test void durableResumeUsesTheLastCommittedTransactionRatherThanALaterPartiallyProcessedEvent() throws Exception {
        var before = KindFailoverWitness.checkpoint(HISTORY, offsets(KEY,
                "{\"lsn\":900,\"lsn_commit\":700,\"snapshot\":false}"), IDENTITY);
        var after = KindFailoverWitness.checkpoint(HISTORY, offsets(KEY,
                "{\"lsn\":1000,\"lsn_commit\":800,\"snapshot\":\"false\"}"), IDENTITY);
        assertEquals(700, before.position());
        assertEquals(800, after.position());
        KindFailoverWitness.retained(before, after, true);
        assertEquals(900, KindFailoverWitness.checkpoint(HISTORY, offsets(KEY, "{\"lsn\":900}"), IDENTITY).position());
    }

    @Test void missingDuplicateWrongConnectorAndSnapshotOffsetsCannotProveResume() {
        assertThrows(IOException.class, () -> KindFailoverWitness.checkpoint(HISTORY, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(), IDENTITY));
        var duplicate = offsets(KEY, "{\"lsn\":700}");
        duplicate.add(duplicate.get(0).deepCopy());
        assertThrows(IOException.class, () -> KindFailoverWitness.checkpoint(HISTORY, duplicate, IDENTITY));
        assertThrows(IOException.class, () -> KindFailoverWitness.checkpoint(HISTORY,
                offsets(KEY.replace("shard_a", "shard_b"), "{\"lsn\":700}"), IDENTITY));
        for (String value : List.of("{\"lsn\":700,\"snapshot\":true}", "{\"lsn\":700,\"snapshot\":\"last\"}",
                "{\"lsn\":700,\"snapshot\":false,\"snapshot_completed\":false}", "{\"lsn\":700,\"lsn_commit\":\"700\"}",
                "{\"lsn\":0}", "{\"lsn\":9223372036854775808}", "{\"lsn\":700,\"snapshot\":12}"))
            assertThrows(IOException.class, () -> KindFailoverWitness.checkpoint(HISTORY, offsets(KEY, value), IDENTITY), value);
    }

    @Test void recreatedHistoryReplacedPartitionAndOffsetRewindFailEvenWhenTheConnectorIsLive() throws Exception {
        var before = new KindFailoverWitness.Cdc(HISTORY, KEY, 700);
        KindFailoverWitness.retained(before, new KindFailoverWitness.Cdc(HISTORY, KEY, 700), false);
        assertThrows(IOException.class, () -> KindFailoverWitness.retained(before,
                new KindFailoverWitness.Cdc(HISTORY.replace("00:00:00", "00:00:01"), KEY, 800), false));
        assertThrows(IOException.class, () -> KindFailoverWitness.retained(before,
                new KindFailoverWitness.Cdc(HISTORY, KEY.replace("shard_a", "shard_b"), 800), false));
        assertThrows(IOException.class, () -> KindFailoverWitness.retained(before, new KindFailoverWitness.Cdc(HISTORY, KEY, 699), false));
        assertThrows(IOException.class, () -> KindFailoverWitness.retained(before, before, true));
    }

    @Test void promotionRequiresAPersistentSyncedStandbySlotCoveringTheSavedCheckpoint() throws Exception {
        ObjectNode standby = slot(true);
        KindFailoverWitness.validateSlot(standby, IDENTITY, true, 700);
        ObjectNode primary = slot(false).put("active", true);
        KindFailoverWitness.validateSlot(primary, IDENTITY, false, 700);
        // PG18 may preserve synced=true on the promoted primary; its provenance is not an error.
        KindFailoverWitness.validateSlot(slot(true).put("active", true), IDENTITY, false, 700);
        for (String field : List.of("failover", "same_database", "synced")) {
            ObjectNode broken = standby.deepCopy().put(field, false);
            assertThrows(IOException.class, () -> KindFailoverWitness.validateSlot(broken, IDENTITY, true, 700), field);
        }
        assertThrows(IOException.class, () -> KindFailoverWitness.validateSlot(slot(true).put("temporary", true), IDENTITY, true, 700));
        assertThrows(IOException.class, () -> KindFailoverWitness.validateSlot(slot(true).put("active", true), IDENTITY, true, 700));
        assertThrows(IOException.class, () -> KindFailoverWitness.validateSlot(slot(true).put("confirmed", 699), IDENTITY, true, 700));
        assertThrows(IOException.class, () -> KindFailoverWitness.validateSlot(slot(true).put("wal_status", "lost"), IDENTITY, true, 700));
        assertThrows(IOException.class, () -> KindFailoverWitness.validateSlot(slot(true).put("invalidation_reason", "wal_removed"), IDENTITY, true, 700));
        assertThrows(IOException.class, () -> KindFailoverWitness.validateSlot(slot(true).put("slot_name", "replacement"), IDENTITY, true, 700));
        assertThrows(IOException.class, () -> KindFailoverWitness.validateSlot(Json.newObject(), IDENTITY, true, 700));
    }

    @Test void originalTransportAndBusinessIdentityMustSurviveEvenWhenAnOrderEventuallyConfirms() throws Exception {
        var original = accepted();
        KindFailoverWitness.sameIdentity(original, original);
        List<KindFailoverWitness.Accepted> changed = List.of(
                new KindFailoverWitness.Accepted("9", "2", "3", "4", "5", HASH, HASH, 1000, 700),
                new KindFailoverWitness.Accepted("1", "9", "3", "4", "5", HASH, HASH, 1000, 700),
                new KindFailoverWitness.Accepted("1", "2", "9", "4", "5", HASH, HASH, 1000, 700),
                new KindFailoverWitness.Accepted("1", "2", "3", "9", "5", HASH, HASH, 1000, 700),
                new KindFailoverWitness.Accepted("1", "2", "3", "4", "9", HASH, HASH, 1000, 700),
                new KindFailoverWitness.Accepted("1", "2", "3", "4", "5", "b".repeat(64), HASH, 1000, 700),
                new KindFailoverWitness.Accepted("1", "2", "3", "4", "5", HASH, "b".repeat(64), 1000, 700));
        for (var replacement : changed)
            assertThrows(IOException.class, () -> KindFailoverWitness.sameIdentity(original, replacement));
        // A later observation can have a later WAL frontier without changing the acknowledged identity.
        KindFailoverWitness.sameIdentity(original,
                new KindFailoverWitness.Accepted("1", "2", "3", "4", "5", HASH, HASH, 2000, 700));
    }

    @Test void duplicateLedgerEffectsOrAReconciledReplacementCannotPassTheOriginalCommandProof() throws Exception {
        ObjectNode delivery = Json.object("{\"decisions\":1,\"entries\":1,\"bindings\":1,\"saga\":\"2\",\"command\":\"3\"}");
        KindFailoverWitness.verifyDelivery(accepted(), delivery);
        for (String field : List.of("decisions", "entries", "bindings")) {
            for (int wrong : List.of(0, 2)) {
                ObjectNode broken = delivery.deepCopy().put(field, wrong);
                assertThrows(IOException.class, () -> KindFailoverWitness.verifyDelivery(accepted(), broken), field);
            }
        }
        assertThrows(IOException.class, () -> KindFailoverWitness.verifyDelivery(accepted(), delivery.deepCopy().put("saga", "20")));
        assertThrows(IOException.class, () -> KindFailoverWitness.verifyDelivery(accepted(), delivery.deepCopy().put("command", "30")));
    }

    @Test void httpBuyerCreationKeyRoutesToTheSelectedShardWithinItsRegion() throws Exception {
        List<String> eligible = List.of("shard-a", "shard-b", "shard-c");
        for (String target : eligible) {
            String key = KindFailoverWitness.buyerKey("kind-failover-fixture", "EU", eligible, target);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("buyer\0EU\0" + key).getBytes(StandardCharsets.UTF_8));
            assertEquals(target, eligible.get(new BigInteger(1, digest).mod(BigInteger.valueOf(3)).intValue()));
            assertTrue(key.length() <= 128);
        }
        assertThrows(IOException.class, () -> KindFailoverWitness.buyerKey("fixture", "EU", eligible, "peer"));
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode offsets(String key, String value) {
        var rows = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        rows.addObject().put("offset_key", key).put("offset_val", value);
        return rows;
    }

    private static ObjectNode slot(boolean synced) {
        return Json.object("""
                {"slot_name":"shardshop_order_shard_a","slot_type":"logical","plugin":"pgoutput","same_database":true,
                 "failover":true,"synced":%s,"temporary":false,"active":false,"wal_status":"reserved", "invalidation_reason":null,"confirmed":700}
                """.formatted(synced));
    }

    private static KindFailoverWitness.Accepted accepted() {
        return new KindFailoverWitness.Accepted("1", "2", "3", "4", "5", HASH, HASH, 1000, 700);
    }
}
