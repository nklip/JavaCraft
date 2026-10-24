package dev.nklip.javacraft.shardshop.ledger.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcLedgerStoreCleanupTest {
    private static final Instant CUTOFF = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void inboxDeleteLimitsCandidatesAndRechecksRowsAfterSelection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(7);

        assertEquals(7, JdbcLedgerStore.deleteExpiredInbox(connection, CUTOFF, 8));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("LIMIT ?"));
        assertTrue(sql.getValue().contains("target.received_at=expired.received_at"));
        assertTrue(sql.getValue().contains("target.received_at < ?"));
        verify(statement).setTimestamp(1, Timestamp.from(CUTOFF));
        verify(statement).setInt(2, 8);
        verify(statement).setTimestamp(3, Timestamp.from(CUTOFF));
        verify(statement).close();
    }

    @Test
    void outboxDeleteRequiresConfirmedOldAttemptBothBeforeAndAfterSelection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        assertEquals(1, JdbcLedgerStore.deleteExpiredOutbox(connection, CUTOFF, 8));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        String query = sql.getValue();
        assertTrue(query.contains("WHERE published_at IS NOT NULL AND published_at < ?"));
        assertTrue(query.contains("LIMIT ?"));
        assertTrue(query.contains("target.publication_attempt=expired.publication_attempt"));
        assertTrue(query.contains("target.published_at=expired.published_at"));
        assertTrue(query.contains("target.published_at IS NOT NULL AND target.published_at < ?"));
        verify(statement).setTimestamp(1, Timestamp.from(CUTOFF));
        verify(statement).setInt(2, 8);
        verify(statement).setTimestamp(3, Timestamp.from(CUTOFF));
        verify(statement).close();
    }
}
