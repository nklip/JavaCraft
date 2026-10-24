package dev.nklip.javacraft.shardshop.ledger.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JdbcLedgerStoreMetricsTest {
    @Test
    void snapshotCountsOnlyPendingResultsAndAllQuarantinedIncidents() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement timeout = mock(PreparedStatement.class);
        ResultSet timeoutRows = mock(ResultSet.class);
        PreparedStatement outbox = mock(PreparedStatement.class);
        PreparedStatement conflicts = mock(PreparedStatement.class);
        ResultSet outboxRows = mock(ResultSet.class);
        ResultSet conflictRows = mock(ResultSet.class);
        List<String> sql = new ArrayList<>();
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            sql.add(query);
            if (query.contains("set_config")) return timeout;
            if (query.contains("ledger.ledger_outbox")) return outbox;
            if (query.contains("ledger.conflicting_commands")) return conflicts;
            throw new AssertionError(query);
        });
        when(outbox.executeQuery()).thenReturn(outboxRows);
        when(timeout.executeQuery()).thenReturn(timeoutRows);
        when(timeoutRows.next()).thenReturn(true);
        when(conflicts.executeQuery()).thenReturn(conflictRows);
        when(outboxRows.getLong(1)).thenReturn(7L);
        when(outboxRows.getDouble(2)).thenReturn(12.5);
        when(conflictRows.getLong(1)).thenReturn(3L);

        var snapshot = JdbcLedgerStore.readMetricsSnapshot(connection,
                new DatabasePhases.Deadline(() -> 0L, 3_000_000_000L));

        assertEquals(7, snapshot.pendingResults());
        assertEquals(12.5, snapshot.oldestPendingAgeSeconds());
        assertEquals(3, snapshot.conflictingCommands());
        assertTrue(sql.stream().anyMatch(query -> query.contains("min(created_at)")
                && query.contains("published_at IS NULL")));
        assertTrue(sql.stream().anyMatch(query -> query.equals("SELECT count(*) FROM ledger.conflicting_commands")));
        verify(connection, times(2)).setNetworkTimeout(any(), anyInt());
    }

    @Test
    void queryFailureCannotBeMistakenForAnEmptySnapshot() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement timeout = mock(PreparedStatement.class);
        ResultSet timeoutRows = mock(ResultSet.class);
        when(timeout.executeQuery()).thenReturn(timeoutRows);
        when(timeoutRows.next()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("set_config")) return timeout;
            throw new java.sql.SQLException("database unavailable");
        });

        assertThrows(java.sql.SQLException.class, () -> JdbcLedgerStore.readMetricsSnapshot(connection,
                new DatabasePhases.Deadline(() -> 0L, 3_000_000_000L)));
    }
}
