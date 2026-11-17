package dev.nklip.javacraft.shardshop.order.cdc;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CdcStoreTest {
    private static final CdcStore.Slot SLOT = new CdcStore.Slot(true, false, "reserved", null, "pgoutput",
            "logical", true, true, 100L);

    @Test
    void publicationWindowUsesOneTransactionAndPreservesExistingPublicationTime() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(2);
        Endpoint endpoint = new Endpoint("jdbc:postgresql://localhost/shardshop", "app", "");
        CdcStore store = new CdcStore(endpoint, endpoint, () -> connection);

        store.markPublished(List.of("123", "124"));

        var order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).prepareStatement(argThat(sql -> sql.contains("COALESCE(published_at, clock_timestamp())")
                && sql.contains("WHERE message_id IN (?,?)")));
        order.verify(statement).setQueryTimeout(3);
        order.verify(statement).setLong(1, 123L);
        order.verify(statement).setLong(2, 124L);
        order.verify(statement).executeUpdate();
        order.verify(statement).close();
        order.verify(connection).commit();
        verify(connection, never()).rollback();
        verify(connection, times(1)).prepareStatement(anyString());
    }

    @Test
    void missingRowRollsBackWholePublicationWindow() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        Endpoint endpoint = new Endpoint("jdbc:postgresql://localhost/shardshop", "app", "");
        CdcStore store = new CdcStore(endpoint, endpoint, () -> connection);

        assertThrows(SQLException.class, () -> store.markPublished(List.of("123", "124")));

        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void failedUpdateRollsBackBeforeReportingFailure() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        SQLException failure = new SQLException("write unavailable");
        when(statement.executeUpdate()).thenThrow(failure);
        Endpoint endpoint = new Endpoint("jdbc:postgresql://localhost/shardshop", "app", "");
        CdcStore store = new CdcStore(endpoint, endpoint, () -> connection);

        assertSame(failure, assertThrows(SQLException.class, () -> store.markPublished(List.of("123", "124"))));

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void uncertainCommitIsReportedEvenIfRollbackAlsoFails() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(2);
        SQLException unknown = new SQLException("connection lost during commit");
        SQLException rollback = new SQLException("connection closed");
        doThrow(unknown).when(connection).commit();
        doThrow(rollback).when(connection).rollback();
        Endpoint endpoint = new Endpoint("jdbc:postgresql://localhost/shardshop", "app", "");
        CdcStore store = new CdcStore(endpoint, endpoint, () -> connection);

        assertSame(unknown, assertThrows(SQLException.class, () -> store.markPublished(List.of("123", "124"))));

        assertArrayEquals(new Throwable[] {rollback}, unknown.getSuppressed());
        verify(connection).close();
    }

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

    @Test
    void cleanupRequiresACompletedStreamingOffsetAndPrefersCommitLsn() {
        Endpoint endpoint = new Endpoint("jdbc:postgresql://localhost/shardshop", "app", "");
        CdcStore store = new CdcStore(endpoint, endpoint);
        String identity = "shardshop_order_shard_a";
        String key = "[\"shardshop_order_shard_a\",{\"server\":\"shardshop_order_shard_a\"}]";
        assertEquals(100L, store.validateStreamingOffset(key,
                "{\"lsn\":200,\"lsn_commit\":100,\"snapshot\":\"false\"}", identity));
        assertEquals(200L, store.validateStreamingOffset(key,
                "{\"lsn\":200,\"snapshot\":false}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateStreamingOffset(key, "{\"lsn\":200,\"snapshot\":\"true\"}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateStreamingOffset(key, "{\"lsn\":200,\"snapshot\":\"last\"}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateStreamingOffset(key,
                        "{\"lsn\":200,\"snapshot_completed\":false}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateStreamingOffset(key, "{\"lsn\":200,\"snapshot\":null}", identity));
        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.validateStreamingOffset("[\"other\",{\"server\":\"other\"}]",
                        "{\"lsn\":200,\"snapshot\":false}", identity));
    }
}
