package dev.nklip.javacraft.shardshop.order.cdc;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TransportCleanupStoreTest {
    private static final Endpoint ENDPOINT = new Endpoint("jdbc:postgresql://localhost/shardshop", "app", "");

    @Test
    void capturesOnlyPublishedOldIdsBeforeWalBarrier() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement wal = mock(PreparedStatement.class);
        ResultSet candidates = mock(ResultSet.class);
        ResultSet position = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(select, wal);
        when(select.executeQuery()).thenReturn(candidates);
        when(wal.executeQuery()).thenReturn(position);
        when(candidates.next()).thenReturn(true, true, false);
        when(candidates.getLong(1)).thenReturn(1L, 2L);
        when(position.next()).thenReturn(true);
        when(position.getLong(1)).thenReturn(150L);
        var store = new TransportCleanupStore(ENDPOINT, () -> connection);

        var fence = store.capture(1_000_000L, 8);

        assertEquals(List.of(1L, 2L), fence.messageIds());
        assertEquals(150L, fence.walPosition());
        var sql = inOrder(connection, select, wal);
        sql.verify(connection).prepareStatement(argThat(query -> query.contains("published_at IS NOT NULL")
                && query.contains("published_at < statement_timestamp()")
                && query.contains("LIMIT ?")));
        sql.verify(select).executeQuery();
        sql.verify(connection).prepareStatement(argThat(query -> query.contains("pg_current_wal_lsn()")));
        sql.verify(wal).executeQuery();
        verify(select).setLong(1, 1_000_000L);
        verify(select).setInt(2, 8);
    }

    @Test
    void deletesUseBoundedCandidatesAndRecheckAgeAndPublication() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement inbox = mock(PreparedStatement.class);
        PreparedStatement outbox = mock(PreparedStatement.class);
        Array ids = mock(Array.class);
        when(connection.prepareStatement(anyString())).thenReturn(inbox, outbox);
        when(connection.createArrayOf(eq("bigint"), any())).thenReturn(ids);
        when(inbox.executeUpdate()).thenReturn(3);
        when(outbox.executeUpdate()).thenReturn(2);
        var store = new TransportCleanupStore(ENDPOINT, () -> connection);

        assertEquals(3, store.deleteInbox(1_000_000L, 8));
        assertEquals(2, store.deleteOutbox(new TransportCleanupStore.Fence(List.of(1L, 2L), 100L), 1_000_000L));

        verify(connection).prepareStatement(argThat(query -> query.contains("ORDER BY received_at, message_id LIMIT ?")
                && query.contains("DELETE FROM ordering.order_inbox")
                && query.contains("i.received_at < statement_timestamp()")
                && !query.contains("FOR UPDATE")));
        verify(inbox).setInt(2, 8);
        verify(inbox).setLong(3, 1_000_000L);
        verify(connection).prepareStatement(argThat(query -> query.contains("message_id = ANY (?)")
                && query.contains("published_at IS NOT NULL")
                && query.contains("published_at < statement_timestamp()")));
        verify(connection).createArrayOf("bigint", new Long[] {1L, 2L});
        verify(outbox).setArray(1, ids);
        verify(ids).free();
    }

    @Test
    void ambiguousOffsetCannotAuthorizeDeletion() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement history = mock(PreparedStatement.class);
        PreparedStatement offset = mock(PreparedStatement.class);
        ResultSet historyRows = mock(ResultSet.class);
        ResultSet offsetRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(history, offset);
        when(history.executeQuery()).thenReturn(historyRows);
        when(historyRows.next()).thenReturn(true);
        when(historyRows.getBoolean(1)).thenReturn(true);
        when(offset.executeQuery()).thenReturn(offsetRows);
        when(offsetRows.next()).thenReturn(true, true);
        when(offsetRows.getString(1)).thenReturn("[\"shardshop_order_shard_a\",{\"server\":\"shardshop_order_shard_a\"}]");
        when(offsetRows.getString(2)).thenReturn("{\"lsn\":100,\"snapshot\":\"false\"}");
        var store = new TransportCleanupStore(ENDPOINT, () -> connection);

        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> store.durableCheckpoint("shardshop_order_shard_a"));
        verify(connection).prepareStatement(argThat(query -> query.contains("debezium_offset_storage LIMIT 2")));
    }

    @Test
    void missingReaderHistoryOrOffsetCannotAuthorizeDeletion() throws SQLException {
        Connection noHistory = mock(Connection.class);
        PreparedStatement history = mock(PreparedStatement.class);
        ResultSet historyRows = mock(ResultSet.class);
        when(noHistory.prepareStatement(anyString())).thenReturn(history);
        when(history.executeQuery()).thenReturn(historyRows);
        when(historyRows.next()).thenReturn(true);
        when(historyRows.getBoolean(1)).thenReturn(false);
        assertNull(new TransportCleanupStore(ENDPOINT, () -> noHistory)
                .durableCheckpoint("shardshop_order_shard_a"));
        verify(noHistory, times(1)).prepareStatement(anyString());

        Connection noOffset = mock(Connection.class);
        PreparedStatement initialized = mock(PreparedStatement.class);
        PreparedStatement offset = mock(PreparedStatement.class);
        ResultSet initializedRows = mock(ResultSet.class);
        ResultSet offsetRows = mock(ResultSet.class);
        when(noOffset.prepareStatement(anyString())).thenReturn(initialized, offset);
        when(initialized.executeQuery()).thenReturn(initializedRows);
        when(initializedRows.next()).thenReturn(true);
        when(initializedRows.getBoolean(1)).thenReturn(true);
        when(offset.executeQuery()).thenReturn(offsetRows);
        when(offsetRows.next()).thenReturn(false);
        assertNull(new TransportCleanupStore(ENDPOINT, () -> noOffset)
                .durableCheckpoint("shardshop_order_shard_a"));
    }

    @Test
    void malformedDurableOffsetFailsClosed() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement history = mock(PreparedStatement.class);
        PreparedStatement offset = mock(PreparedStatement.class);
        ResultSet historyRows = mock(ResultSet.class);
        ResultSet offsetRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(history, offset);
        when(history.executeQuery()).thenReturn(historyRows);
        when(historyRows.next()).thenReturn(true);
        when(historyRows.getBoolean(1)).thenReturn(true);
        when(offset.executeQuery()).thenReturn(offsetRows);
        when(offsetRows.next()).thenReturn(true, false);
        when(offsetRows.getString(1)).thenReturn("not json");
        when(offsetRows.getString(2)).thenReturn("{\"lsn\":123}");

        assertThrows(CdcStore.RecoveryRequiredException.class,
                () -> new TransportCleanupStore(ENDPOINT, () -> connection)
                        .durableCheckpoint("shardshop_order_shard_a"));
    }
}
