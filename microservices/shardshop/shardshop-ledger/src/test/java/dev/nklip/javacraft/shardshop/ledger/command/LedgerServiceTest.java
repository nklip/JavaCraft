package dev.nklip.javacraft.shardshop.ledger.command;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class LedgerServiceTest {
    private static final Instant FIRST = Instant.parse("2026-10-06T12:00:01.123456789Z");
    private static final Instant LATER = Instant.parse("2026-10-06T12:02:00Z");
    private static final Path EXAMPLE = Path.of("../shardshop-order/src/main/resources/contracts/examples/record-order.json");

    private static String example() throws IOException {
        return Files.readString(EXAMPLE);
    }

    private static CommandOutcome handle(FakeStore store, String payload, Set<String> currencies, Instant at) {
        return new LedgerService(store, currencies, Clock.fixed(at, ZoneOffset.UTC))
                .handle(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void firstAllowedAndRejectedDecisionsSaveSchemaValidFields() throws IOException {
        FakeStore allowed = new FakeStore();
        assertEquals(new CommandOutcome(CommandDisposition.APPLIED, 100925016375300099L, 100925016375300096L, null),
                handle(allowed, example(), Set.of("EUR"), FIRST));
        assertEquals("RECORDED", allowed.decisions.getFirst().outcome());
        assertNull(allowed.decisions.getFirst().rejectionReason());
        ObjectNode recorded = allowed.results.get(100925016375300100L);
        assertEquals("LedgerRecorded", recorded.path("type").textValue());
        assertEquals("39.90", recorded.path("amount").textValue());
        assertEquals("100925016375300100", recorded.path("messageId").textValue());
        assertEquals("2026-10-06T12:00:01.123456Z", recorded.path("decidedAt").textValue());

        FakeStore rejected = new FakeStore();
        assertEquals(CommandDisposition.APPLIED, handle(rejected, example(), Set.of("USD"), FIRST).disposition());
        assertEquals("REJECTED", rejected.decisions.getFirst().outcome());
        assertEquals("UNSUPPORTED_CURRENCY", rejected.results.get(100925016375300100L)
                .path("rejectionReason").textValue());
    }

    @Test
    void sameTransportReplayRearmsExactSavedResultAfterCleanupAndPolicyChange() throws IOException {
        FakeStore store = new FakeStore();
        handle(store, example(), Set.of("EUR"), FIRST);
        ObjectNode originalResult = store.results.get(100925016375300100L).deepCopy();
        store.inboxCount = 0;

        assertEquals(CommandDisposition.REPLAYED, handle(store, example(), Set.of("USD"), LATER).disposition());
        assertEquals(1, store.decisions.size());
        assertEquals("RECORDED", store.decisions.getFirst().outcome());
        assertEquals(originalResult, store.results.get(100925016375300100L));
        assertEquals(2L, store.attempts.get(100925016375300100L).longValue());
        assertEquals(1, store.inboxCount);
    }

    @Test
    void newTransportPairReusesDecisionWithNewOccurrenceTime() throws IOException {
        FakeStore store = new FakeStore();
        String original = example();
        handle(store, original, Set.of("EUR"), FIRST);
        String reconciliation = original.replace("100925016375300099", "100925016375300101")
                .replace("100925016375300100", "100925016375300102")
                .replace("2026-10-06T12:00:00Z", "2026-10-06T12:01:00Z");

        assertEquals(CommandDisposition.REPLAYED, handle(store, reconciliation, Set.of("USD"), LATER).disposition());
        assertEquals(1, store.decisions.size());
        assertEquals(2, store.bound.size());
        ObjectNode replay = store.results.get(100925016375300102L);
        assertEquals("RECORDED", replay.path("outcome").textValue());
        assertEquals("2026-10-06T12:00:01.123456Z", replay.path("decidedAt").textValue());
        assertEquals(LATER.toString(), replay.path("occurredAt").textValue());
    }

    @Test
    void changedFullPayloadAndSwappedTransportRolesQuarantineWithoutRearming() throws IOException {
        FakeStore store = new FakeStore();
        String original = example();
        handle(store, original, Set.of("EUR"), FIRST);
        String changedTime = original.replace("2026-10-06T12:00:00Z", "2026-10-06T12:00:01Z");
        assertEquals(new CommandOutcome(CommandDisposition.QUARANTINED, 100925016375300099L, 100925016375300096L,
                "PAYLOAD_CONFLICT"), handle(store, changedTime, Set.of("EUR"), LATER));
        assertEquals("PAYLOAD_CONFLICT", store.conflictReason);
        String swapped = original.replace("100925016375300099", "TEMP")
                .replace("100925016375300100", "100925016375300099")
                .replace("TEMP", "100925016375300100");
        assertEquals(CommandDisposition.QUARANTINED, handle(store, swapped, Set.of("EUR"), LATER).disposition());
        assertEquals("TRANSPORT_IDENTITY_CONFLICT", store.conflictReason);
        assertEquals(1L, store.attempts.get(100925016375300100L).longValue());
    }

    /** Step 4.5 regression: the fingerprint excludes product names, thus only the snapshot comparison sees this. */
    @Test
    void newTransportPairWithAChangedSnapshotIsQuarantinedWithoutABinding() throws IOException {
        FakeStore store = new FakeStore();
        String original = example();
        handle(store, original, Set.of("EUR"), FIRST);
        String changed = original.replace("100925016375300099", "100925016375300101")
                .replace("100925016375300100", "100925016375300102")
                .replace("\"productName\": \"Travel mug\"", "\"productName\": \"Changed mug\"");

        assertEquals(new CommandOutcome(CommandDisposition.QUARANTINED, 100925016375300101L, 100925016375300096L,
                "BUSINESS_IDENTITY_CONFLICT"), handle(store, changed, Set.of("EUR"), LATER));
        assertEquals("BUSINESS_IDENTITY_CONFLICT", store.conflictReason);
        assertEquals(1, store.bound.size());
        assertNull(store.results.get(100925016375300102L));
    }

    /** Step 4.5 regression: a ledger clock behind the saved decision cannot date a result before that decision. */
    @Test
    void reconciliationResultNeverPredatesItsDecision() throws IOException {
        FakeStore store = new FakeStore();
        String original = example();
        handle(store, original, Set.of("EUR"), LATER);
        String reconciliation = original.replace("100925016375300099", "100925016375300101")
                .replace("100925016375300100", "100925016375300102");

        assertEquals(CommandDisposition.REPLAYED, handle(store, reconciliation, Set.of("EUR"), FIRST).disposition());
        ObjectNode replay = store.results.get(100925016375300102L);
        assertEquals(LATER.toString(), replay.path("decidedAt").textValue());
        assertEquals(LATER.toString(), replay.path("occurredAt").textValue());
    }

    @Test
    void malformedCommandNeverOpensATransaction() {
        FakeStore store = new FakeStore();
        assertThrows(MalformedCommandException.class, () -> handle(store, "{}", Set.of("USD"), FIRST));
        assertEquals(0, store.transactions);
    }

    private static final class FakeStore implements LedgerStore, LedgerStore.Session {
        private final List<LedgerDecision> decisions = new ArrayList<>();
        private final List<TransportBinding> bound = new ArrayList<>();
        private final Map<Long, ObjectNode> results = new HashMap<>();
        private final Map<Long, Long> attempts = new HashMap<>();
        private int inboxCount;
        private int transactions;
        private String conflictReason;

        @Override
        public <T> T write(LedgerCommand command, Function<Session, T> work) {
            transactions++;
            return work.apply(this);
        }

        @Override
        public List<LedgerDecision> operations(LedgerCommand command) {
            Set<Long> ids = Set.of(command.orderId(), command.sagaId(), command.commandId());
            return decisions.stream().filter(value -> ids.contains(value.orderId())
                    || ids.contains(value.sagaId()) || ids.contains(value.commandId())).toList();
        }

        @Override
        public List<TransportBinding> bindings(LedgerCommand command) {
            return bound.stream().filter(value -> value.messageId() == command.messageId()
                    || value.messageId() == command.resultMessageId()
                    || value.resultMessageId() == command.messageId()
                    || value.resultMessageId() == command.resultMessageId()).toList();
        }

        @Override
        public void insertDecision(LedgerCommand command, LedgerDecision decision) { decisions.add(decision); }

        @Override
        public void insertBinding(LedgerCommand command, ObjectNode envelope, Instant now) {
            bound.add(new TransportBinding(command.messageId(), command.resultMessageId(), command.orderId(),
                    command.envelope()));
            results.put(command.resultMessageId(), envelope);
        }

        @Override
        public void insertInbox(LedgerCommand command, Instant now) { inboxCount++; }

        @Override
        public void insertOutbox(long resultMessageId) { attempts.put(resultMessageId, 1L); }

        @Override
        public long incrementAttempt(long resultMessageId) {
            long next = attempts.get(resultMessageId) + 1;
            attempts.put(resultMessageId, next);
            return next;
        }

        @Override
        public void rearmOutbox(long resultMessageId, long attempt) {
            assertEquals(attempt, attempts.get(resultMessageId).longValue());
        }

        @Override
        public void quarantine(LedgerCommand command, ObjectNode expected, ObjectNode received,
                               String reason, Instant now) {
            conflictReason = reason;
        }
    }
}
