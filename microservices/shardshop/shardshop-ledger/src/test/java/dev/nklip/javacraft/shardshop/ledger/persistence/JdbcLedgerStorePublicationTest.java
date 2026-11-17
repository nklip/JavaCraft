package dev.nklip.javacraft.shardshop.ledger.persistence;

import dev.nklip.javacraft.shardshop.ledger.result.PendingResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcLedgerStorePublicationTest {
    @Test
    void marksWindowWithOneStatementAndPreservesEveryObservedAttempt() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        List<PendingResult> window = List.of(new PendingResult(43, 7, "{}"), new PendingResult(42, 3, "{}"));

        assertEquals(1, JdbcLedgerStore.markPublished(connection, window));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        String query = sql.getValue();
        assertTrue(query.contains("observed(message_id, publication_attempt) AS (VALUES"));
        assertTrue(query.contains("target.message_id=observed.message_id"));
        assertTrue(query.contains("target.publication_attempt=observed.publication_attempt"));
        assertTrue(query.contains("ORDER BY target.message_id FOR UPDATE OF target"));
        assertTrue(query.contains("target.message_id=eligible.message_id"));
        assertTrue(query.contains("target.publication_attempt=eligible.publication_attempt"));
        assertTrue(query.endsWith("AND target.published_at IS NULL"));
        verify(statement).setLong(1, 43);
        verify(statement).setLong(2, 7);
        verify(statement).setLong(3, 42);
        verify(statement).setLong(4, 3);
        verify(statement).executeUpdate();
        verify(statement).close();
    }

    @Test
    void refusesUnboundedOrDuplicateMarkWindowsBeforeDatabaseIo() {
        Connection connection = mock(Connection.class);
        PendingResult result = new PendingResult(42, 3, "{}");
        List<PendingResult> oversized = LongStream.rangeClosed(1, 17)
                .mapToObj(id -> new PendingResult(id, 1, "{}")).toList();

        assertThrows(IllegalArgumentException.class, () -> JdbcLedgerStore.markPublished(connection, List.of()));
        assertThrows(IllegalArgumentException.class, () -> JdbcLedgerStore.markPublished(connection, oversized));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcLedgerStore.markPublished(connection, List.of(result, new PendingResult(42, 4, "{}"))));
        verifyNoInteractions(connection);
    }

    @Test
    void failedBatchMarkDoesNotReportPartialSuccess() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new SQLException("transaction failed"));

        assertThrows(SQLException.class, () -> JdbcLedgerStore.markPublished(connection,
                List.of(new PendingResult(42, 3, "{}"), new PendingResult(43, 7, "{}"))));
        verify(statement).close();
    }
}
