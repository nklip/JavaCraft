package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.profit.ProfitFailure;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore.PendingCredit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcProfitSessionTest {
    private final PendingCredit credit = new PendingCredit(41, 0, 51, 61, "USD", new BigDecimal("-2.50"), 0);
    private final Connection connection = mock();
    private final PreparedStatement insert = mock();
    private final PreparedStatement balanceInsert = mock();
    private final PreparedStatement balanceUpdate = mock();
    private final PreparedStatement existing = mock();
    private final ResultSet inserted = mock();
    private final ResultSet saved = mock();
    private JdbcProfitSession session;

    @BeforeEach
    void setup() throws SQLException {
        PreparedStatement timeouts = mock();
        ResultSet configured = mock();
        when(configured.next()).thenReturn(true);
        when(timeouts.executeQuery()).thenReturn(configured);
        when(connection.prepareStatement(startsWith("SELECT set_config"))).thenReturn(timeouts);
        when(connection.prepareStatement(startsWith("INSERT INTO catalog.profit_credits"))).thenReturn(insert);
        when(connection.prepareStatement(startsWith("INSERT INTO catalog.seller_profits"))).thenReturn(balanceInsert);
        when(connection.prepareStatement(startsWith("UPDATE catalog.seller_profits"))).thenReturn(balanceUpdate);
        when(connection.prepareStatement(startsWith("SELECT seller_id, currency, amount"))).thenReturn(existing);
        when(insert.executeQuery()).thenReturn(inserted);
        when(existing.executeQuery()).thenReturn(saved);
        when(balanceUpdate.executeUpdate()).thenReturn(1);
        session = new JdbcProfitSession(connection, new DatabasePhases.Deadline(() -> 0, 2_000_000_000L));
    }

    @Test
    void newCreditAndSignedBalanceUpdateShareTheCallingTransaction() throws SQLException {
        when(inserted.next()).thenReturn(true);
        session.creditProfit(credit);
        var order = inOrder(insert, balanceInsert, balanceUpdate);
        order.verify(insert).executeQuery();
        order.verify(balanceInsert).executeUpdate();
        order.verify(balanceUpdate).executeUpdate();
        verify(balanceUpdate).setBigDecimal(1, new BigDecimal("-2.50"));
        verify(connection, never()).commit();
    }

    @Test
    void identicalCreditReplayDoesNotChangeBalance() throws SQLException {
        when(inserted.next()).thenReturn(false);
        when(saved.next()).thenReturn(true);
        when(saved.getLong("seller_id")).thenReturn(51L);
        when(saved.getString("currency")).thenReturn("USD");
        when(saved.getBigDecimal("amount")).thenReturn(new BigDecimal("-2.50"));
        session.creditProfit(credit);
        verify(balanceInsert, never()).executeUpdate();
        verify(balanceUpdate, never()).executeUpdate();
    }

    @Test
    void conflictingReplayFailsClosedWithoutChangingBalance() throws SQLException {
        when(inserted.next()).thenReturn(false);
        when(saved.next()).thenReturn(true);
        when(saved.getLong("seller_id")).thenReturn(51L);
        when(saved.getString("currency")).thenReturn("USD");
        when(saved.getBigDecimal("amount")).thenReturn(new BigDecimal("3.00"));
        ProfitFailure failure = assertThrows(ProfitFailure.class, () -> session.creditProfit(credit));
        assertEquals(ProfitFailure.Reason.CREDIT_CONFLICT, failure.reason());
        verify(balanceInsert, never()).executeUpdate();
        verify(balanceUpdate, never()).executeUpdate();
    }
}
