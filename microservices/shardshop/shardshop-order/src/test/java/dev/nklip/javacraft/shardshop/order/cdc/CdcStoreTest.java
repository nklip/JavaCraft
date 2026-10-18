package dev.nklip.javacraft.shardshop.order.cdc;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CdcStoreTest {
    private static final CdcStore.Slot SLOT = new CdcStore.Slot(true, false, "reserved", null, "pgoutput",
            "logical", true, true, 100L);

    @Test
    void startsOnlyFromAFreshStateAnUncheckpointedFirstStartOrAPairedPosition() {
        assertNull(CdcStore.recoveryReason(false, null, CdcStore.Slot.ABSENT), "fresh reader");
        assertNull(CdcStore.recoveryReason(false, null, SLOT), "first start that failed before its first checkpoint");
        assertNull(CdcStore.recoveryReason(true, 100L, SLOT), "paired offset and slot");
        assertNull(CdcStore.recoveryReason(true, 150L, SLOT), "Debezium advances a slot that is behind its offset");
    }

    /** Only a slot of this connector's shape without any saved checkpoint may be dropped and recreated. */
    @Test
    void recreatesOnlyAnUncheckpointedSlotOfThisConnectorShape() {
        assertTrue(CdcStore.uncheckpointed(false, null, SLOT));
        assertTrue(CdcStore.uncheckpointed(false, null,
                new CdcStore.Slot(true, false, "lost", "wal_removed", "pgoutput", "logical", true, true, 100L)));
        assertTrue(CdcStore.uncheckpointed(false, null,
                new CdcStore.Slot(true, false, "reserved", null, "pgoutput", "logical", true, false, 100L)));
        assertFalse(CdcStore.uncheckpointed(false, null, CdcStore.Slot.ABSENT));
        assertFalse(CdcStore.uncheckpointed(true, null, SLOT), "reader history proves an earlier checkpoint");
        assertFalse(CdcStore.uncheckpointed(false, 100L, SLOT), "an offset is a checkpoint");
        assertFalse(CdcStore.uncheckpointed(false, null,
                new CdcStore.Slot(true, false, "reserved", null, "test_decoding", "logical", true, true, 100L)));
        assertFalse(CdcStore.uncheckpointed(false, null,
                new CdcStore.Slot(true, false, "reserved", null, "pgoutput", "logical", false, true, 100L)));
    }

    @Test
    void namesEveryStateThatNeedsReconciliation() {
        assertEquals("durable offset exists without its replication slot",
                CdcStore.recoveryReason(true, 100L, CdcStore.Slot.ABSENT));
        assertEquals("replication slot exists without a durable offset",
                CdcStore.recoveryReason(true, null, SLOT));
        assertEquals("reader history exists without its offset and replication slot",
                CdcStore.recoveryReason(true, null, CdcStore.Slot.ABSENT));
        assertEquals("durable offset exists without reader history",
                CdcStore.recoveryReason(false, 100L, SLOT));
        assertEquals("replication slot is ahead of its durable offset",
                CdcStore.recoveryReason(true, 99L, SLOT));
        assertEquals("replication slot was invalidated (wal_removed)", CdcStore.recoveryReason(true, 100L,
                new CdcStore.Slot(true, false, "lost", "wal_removed", "pgoutput", "logical", true, true, 100L)));
        assertEquals("replication slot was invalidated (wal_status lost)", CdcStore.recoveryReason(false, null,
                new CdcStore.Slot(true, false, "lost", null, "pgoutput", "logical", true, true, 100L)));
        assertEquals("replication slot is not a failover pgoutput slot of this database", CdcStore.recoveryReason(
                true, 100L, new CdcStore.Slot(true, false, "reserved", null, "pgoutput", "logical", true, false, 100L)));
        assertEquals("replication slot is not a failover pgoutput slot of this database", CdcStore.recoveryReason(
                false, null, new CdcStore.Slot(true, false, "reserved", null, "test_decoding", "logical", true, true, 100L)));
    }

    @Test
    void resumesFromTheLastStoredCommitOfTheExactConnectorPartition() {
        Endpoint endpoint = new Endpoint("jdbc:postgresql://localhost/shardshop", "app", "");
        CdcStore store = new CdcStore(endpoint, endpoint);
        String identity = "shardshop_order_shard_a";
        String expectedKey = "[\"shardshop_order_shard_a\",{\"server\":\"shardshop_order_shard_a\"}]";

        assertEquals(1234567L, store.validateOffset(expectedKey,
                "{\"lsn\":1234567,\"snapshot\":\"true\",\"snapshot_completed\":false}", identity));
        assertEquals(1200000L, store.validateOffset(expectedKey,
                "{\"lsn\":1234567,\"lsn_commit\":1200000,\"lsn_proc\":1234567}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateOffset("[\"other\",{\"server\":\"shardshop_order_shard_a\"}]",
                        "{\"lsn\":1234567}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateOffset("[\"shardshop_order_shard_a\",{\"wrong\":\"shardshop_order_shard_a\"}]",
                        "{\"lsn\":1234567}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateOffset(expectedKey, "{}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateOffset(expectedKey, "{\"lsn\":\"not-a-position\"}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateOffset(expectedKey, "{\"lsn\":1234567,\"lsn_commit\":\"later\"}", identity));
    }
}
