package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CdcRelay;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.OrderRuntime;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Packaged CDC integration against PostgreSQL logical WAL and the chart's RabbitMQ topology. */
public final class CdcRelayStepDefinitions {
    private static final Duration ATTEMPT = Duration.ofSeconds(20);
    private static final Duration RETRY = Duration.ofSeconds(45);
    private final CatalogShards shards;
    private final RabbitMqBroker broker;
    private final OrderRuntime order;
    private final CdcRelay relay;
    private OrderRuntime second;

    public CdcRelayStepDefinitions(CatalogShards shards, RabbitMqBroker broker, OrderRuntime order, CdcRelay relay) {
        this.shards = shards;
        this.broker = broker;
        this.order = order;
        this.relay = relay;
    }

    @Given("CDC uses migrated TLS shard primaries and the chart broker")
    public void start() throws Exception {
        relay.prepare();
    }

    @After("@cdc-relay")
    public void stop() throws Exception {
        if (second != null) second.stop();
        order.stop();
    }

    @Then("CDC snapshots pending rows, streams each shard, and ignores rollback updates and deletes")
    public void snapshotAndWal() throws Exception {
        var first = shards.all().getFirst();
        var pending = relay.insert(first, false);
        var alreadyPublished = relay.insert(first, true);
        order.enableCdc(broker);
        order.start(Set.of());
        relay.delivery(pending);
        eventually("snapshot row is marked published", () -> relay.published(pending));
        assertTrue(relay.published(alreadyPublished));
        relay.assertNoDelivery(Duration.ofSeconds(2));

        for (var shard : shards.all()) {
            var source = relay.insert(shard, false);
            relay.delivery(source);
            eventually("WAL insert is marked published on " + shard.name(), () -> relay.published(source));
            eventually("WAL insert is checkpointed on " + shard.name(),
                    () -> relay.offsetLsn(shard) > source.walBeforeInsert());
            relay.rolledBackInsert(shard);
            relay.updateAndDelete(source);
        }
        relay.assertNoDelivery(Duration.ofSeconds(2));
        for (var shard : shards.all()) {
            eventually("durable offset for " + shard.name(), () -> relay.offsets(shard) > 0);
        }
    }

    @Then("CDC delivers snapshot rows and concurrent inserts exactly once")
    public void snapshotHandoff() throws Exception {
        var shard = shards.all().getFirst();
        Set<String> expected = new HashSet<>();
        for (int row = 0; row < 120; row++) {
            expected.add(relay.insert(shard, false).messageId());
        }
        order.enableCdc(broker);
        order.start(Set.of());
        // The reader snapshots 120 rows; these commits arrive while that snapshot runs and after it ends.
        for (int row = 0; row < 20; row++) {
            expected.add(relay.insert(shard, false).messageId());
            Thread.sleep(100);
        }
        List<String> delivered = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!delivered.containsAll(expected) && System.nanoTime() < deadline) {
            delivered.addAll(relay.deliveries(Duration.ofSeconds(1)));
        }
        delivered.addAll(relay.deliveries(Duration.ofSeconds(2)));
        assertEquals(expected, new HashSet<>(delivered), "Every snapshot row and concurrent insert arrives");
        assertEquals(expected.size(), delivered.size(), "No command arrives twice without a crash");
        eventually("every handoff command is marked published", () -> relay.pending(shard) == 0);
        assertNoRecovery(order);
    }

    @Then("CDC resumes its persisted per-shard offset after restart")
    public void restart() throws Exception {
        order.enableCdc(broker);
        order.start(Set.of());
        var shard = shards.all().getFirst();
        var source = relay.insert(shard, false);
        relay.delivery(source);
        eventually("initial command has a durable checkpoint", () -> relay.offsetLsn(shard) > source.walBeforeInsert());
        eventually("initial command is marked published", () -> relay.published(source));
        order.stop();
        var afterRestart = relay.insert(source.shard(), false);
        relay.markPublished(afterRestart);
        // A saved WAL position sees its committed insert with published_at NULL. A fresh snapshot
        // sees the later published metadata and would skip the command.
        order.start(Set.of());
        relay.delivery(afterRestart);
        eventually("post-restart WAL insert is checkpointed", () ->
                relay.offsetLsn(shard) > afterRestart.walBeforeInsert());
        relay.assertNoDelivery(Duration.ofSeconds(2));
    }

    @Then("an idle CDC reader checkpoints its empty snapshot and resumes after a restart")
    public void idleRestart() throws Exception {
        order.enableCdc(broker);
        order.start(Set.of());
        for (var shard : shards.all()) {
            eventually("the empty snapshot of " + shard.name() + " has a durable offset and reader history",
                    () -> relay.offsets(shard) == 1 && relay.readerHistory(shard) == 1);
        }
        order.restart(Set.of());
        for (var shard : shards.all()) {
            var source = relay.insert(shard, false);
            relay.delivery(source);
            eventually("the first command after the restart is marked published on " + shard.name(),
                    () -> relay.published(source));
        }
        relay.assertNoDelivery(Duration.ofSeconds(2));
        assertNoRecovery(order);
    }

    @Then("a CDC reader that fails before its first checkpoint delivers after its route recovers")
    public void firstStartRetry() throws Exception {
        var shard = shards.all().getFirst();
        var pending = relay.insert(shard, false);
        relay.unbindCommands();
        order.enableCdc(broker);
        order.start(Set.of());
        eventually("the first snapshot command returns as unroutable",
                () -> retried(order, shard, "RabbitMQ returned unroutable command publication"));
        assertEquals(0, relay.offsets(shard), "No command reached a checkpoint");
        assertEquals(0, relay.readerHistory(shard), "No checkpoint created reader history");
        assertFalse(relay.published(pending));
        relay.bindCommands();
        relay.delivery(pending, RETRY);
        eventually("the retried command is marked published", () -> relay.published(pending));
        eventually("the retried snapshot reaches its first checkpoint",
                () -> relay.offsets(shard) == 1 && relay.readerHistory(shard) == 1);
        relay.assertNoDelivery(Duration.ofSeconds(2));
        assertNoRecovery(order);
    }

    @Then("an idle CDC outbox confirms and releases unrelated WAL")
    public void idleOutboxReleasesWal() throws Exception {
        var shard = shards.all().getFirst();
        checkpoint(shard);
        long churned = relay.churnWal(shard);
        eventually("an idle reader confirms WAL past unrelated writes", RETRY,
                () -> relay.slot(shard).confirmedFlushLsn() >= churned);
        relay.checkpoint(shard);
        eventually("the slot releases the WAL of unrelated writes", RETRY,
                () -> relay.slot(shard).restartLsn() >= churned);
        relay.assertNoDelivery(Duration.ofSeconds(1));
        assertNoRecovery(order);
    }

    @Then("an unavailable CDC shard leaves its command pending while another shard publishes")
    public void shardFailureIsolation() throws Exception {
        var unavailable = shards.all().getFirst();
        var healthy = shards.all().get(1);
        var blocked = relay.insert(unavailable, false);
        var delivered = relay.insert(healthy, false);
        order.enableCdc(broker);
        order.start(Set.of(unavailable.name()));
        relay.delivery(delivered);
        eventually("healthy shard marks its command published", () -> relay.published(delivered));
        eventually("healthy shard checkpoints independently", () -> relay.offsets(healthy) > 0);
        assertFalse(relay.published(blocked), "Unavailable shard retains its source command");
        relay.assertNoDelivery(Duration.ofSeconds(2));
    }

    @Then("an unroutable CDC command stays pending until its route and reader recover")
    public void mandatoryReturn() throws Exception {
        var shard = shards.all().getFirst();
        order.enableCdc(broker);
        order.start(Set.of());
        var baseline = relay.insert(shard, false);
        relay.delivery(baseline);
        eventually("baseline checkpoint is durable", () -> relay.offsetLsn(shard) > baseline.walBeforeInsert());
        long checkpoint = relay.offsetLsn(shard);
        relay.unbindCommands();
        var unroutable = relay.insert(shard, false);
        eventually("CDC reader retries after the unroutable publish and names the cause",
                () -> retried(order, shard, "RabbitMQ returned unroutable command publication"));
        assertFalse(relay.published(unroutable));
        long failedPosition = relay.offsetLsn(shard);
        assertTrue(failedPosition >= checkpoint && failedPosition <= unroutable.walBeforeInsert(),
                "The failed record must not advance the durable source LSN past its insert");
        relay.assertNoDelivery(Duration.ofSeconds(1));
        assertFalse(relay.published(unroutable));
        assertTrue(relay.offsetLsn(shard) <= unroutable.walBeforeInsert(),
                "Retries must not advance the source LSN past the failed command");
        relay.bindCommands();
        relay.delivery(unroutable, Duration.ofSeconds(40));
        eventually("recovered command is marked published", () -> relay.published(unroutable));
    }

    @Then("a terminated CDC walsender reconnects and delivers the next command")
    public void walSenderReconnect() throws Exception {
        var shard = shards.all().getFirst();
        checkpoint(shard);
        relay.terminateWalSender(shard);
        var next = relay.insert(shard, false);
        relay.delivery(next, RETRY);
        eventually("the command after the reconnect is marked published", () -> relay.published(next));
        relay.assertNoDelivery(Duration.ofSeconds(2));
        assertNoRecovery(order);
    }

    @Then("a second order process waits for CDC ownership and takes over after the first stops")
    public void secondProcess() throws Exception {
        var shard = shards.all().getFirst();
        checkpoint(shard);
        second = new OrderRuntime(shards);
        second.enableCdc(broker);
        second.start(Set.of());
        for (var each : shards.all()) {
            eventually("the second process cannot own " + each.name(),
                    () -> retried(second, each, "CDC shard is already owned"));
        }
        var whileOwned = relay.insert(shard, false);
        assertEquals(List.of(whileOwned.messageId()), relay.deliveries(Duration.ofSeconds(5)),
                "Only the owning process publishes, once");
        order.stop();
        var afterTakeover = relay.insert(shard, false);
        relay.delivery(afterTakeover, RETRY);
        eventually("the new owner marks its command published", () -> relay.published(afterTakeover));
        relay.assertNoDelivery(Duration.ofSeconds(2));
        assertNoRecovery(second);
    }

    @Then("a missing CDC slot fails closed")
    public void missingSlot() throws Exception {
        var shard = shards.all().getFirst();
        var baseline = checkpoint(shard);
        order.stop();
        relay.dropSlot(shard);
        var later = relay.insert(shard, false);
        order.start(Set.of());
        eventually("missing slot latches recovery-required state",
                () -> recoveryRequired(shard, "durable offset exists without its replication slot"));
        relay.assertNoDelivery(Duration.ofSeconds(2));
        assertFalse(relay.published(later));
        assertTrue(relay.published(baseline));
    }

    @Then("a missing CDC offset fails closed")
    public void missingOffset() throws Exception {
        var shard = shards.all().getFirst();
        var baseline = checkpoint(shard);
        order.stop();
        relay.deleteOffsets(shard);
        var later = relay.insert(shard, false);
        order.start(Set.of());
        eventually("missing offset latches recovery-required state",
                () -> recoveryRequired(shard, "replication slot exists without a durable offset"));
        relay.assertNoDelivery(Duration.ofSeconds(2));
        assertFalse(relay.published(later));
        assertTrue(relay.published(baseline));
    }

    @Then("missing CDC slot and offset fail closed with immutable reader history")
    public void missingSlotAndOffset() throws Exception {
        var shard = shards.all().getFirst();
        var baseline = checkpoint(shard);
        assertEquals(1, relay.readerHistory(shard), "First durable offset creates permanent reader history");
        relay.assertHistoryImmutableToRuntime(shard);
        order.stop();
        relay.dropSlot(shard);
        relay.deleteOffsets(shard);
        assertEquals(1, relay.readerHistory(shard), "Losing transport metadata cannot erase reader history");
        var later = relay.insert(shard, false);
        order.start(Set.of());
        eventually("lost slot and offset latch recovery-required state",
                () -> recoveryRequired(shard, "reader history exists without its offset and replication slot"));
        relay.assertNoDelivery(Duration.ofSeconds(2));
        assertFalse(relay.published(later));
        assertTrue(relay.published(baseline));
        assertEquals(1, relay.readerHistory(shard));
    }

    @Then("an invalidated CDC slot fails closed")
    public void invalidatedSlot() throws Exception {
        var shard = shards.all().getFirst();
        var baseline = checkpoint(shard);
        order.stop();
        relay.invalidateSlot(shard);
        var later = relay.insert(shard, false);
        order.start(Set.of());
        eventually("lost WAL latches recovery-required state",
                () -> recoveryRequired(shard, "replication slot was invalidated (wal_removed)"));
        relay.assertNoDelivery(Duration.ofSeconds(2));
        assertFalse(relay.published(later));
        assertTrue(relay.published(baseline));
    }

    @Then("a CDC slot ahead of its durable offset fails closed")
    public void slotAhead() throws Exception {
        var shard = shards.all().getFirst();
        var baseline = checkpoint(shard);
        order.stop();
        var skipped = relay.insert(shard, false);
        relay.advanceSlot(shard);
        order.start(Set.of());
        eventually("a slot ahead of its offset latches recovery-required state",
                () -> recoveryRequired(shard, "replication slot is ahead of its durable offset"));
        relay.assertNoDelivery(Duration.ofSeconds(2));
        assertFalse(relay.published(skipped), "The command behind the advanced slot stays pending");
        assertTrue(relay.published(baseline));
    }

    private CdcRelay.Source checkpoint(CatalogShards.Shard shard) throws Exception {
        order.enableCdc(broker);
        order.start(Set.of());
        var source = relay.insert(shard, false);
        relay.delivery(source);
        eventually("baseline command is marked published", () -> relay.published(source));
        eventually("baseline CDC offset is saved", () -> relay.offsetLsn(shard) > source.walBeforeInsert());
        return source;
    }

    private boolean recoveryRequired(CatalogShards.Shard shard, String reason) {
        return problems().anyMatch(line -> line.contains("CDC recovery required for " + shard.name() + ": " + reason));
    }

    /** A retry line names its shard, its delay and the failure that caused it. */
    private static boolean retried(OrderRuntime runtime, CatalogShards.Shard shard, String cause) {
        return runtime.problems().stream().anyMatch(line ->
                line.contains("CDC reader for " + shard.name() + " stopped; retrying in ") && line.contains(cause));
    }

    private static void assertNoRecovery(OrderRuntime runtime) {
        assertTrue(runtime.problems().stream().noneMatch(line -> line.contains("CDC recovery required")),
                () -> "Unexpected CDC recovery: " + runtime.problems());
    }

    private Stream<String> problems() {
        return second == null ? order.problems().stream()
                : Stream.concat(order.problems().stream(), second.problems().stream());
    }

    private static void eventually(String description, Check check) throws Exception {
        eventually(description, ATTEMPT, check);
    }

    private static void eventually(String description, Duration timeout, Check check) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!check.test()) {
            if (System.nanoTime() >= deadline) fail(description + " did not complete within " + timeout);
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean test() throws Exception;
    }
}
