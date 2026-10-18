package dev.nklip.javacraft.shardshop.ledger.config;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerWiringTest {
    @Test
    void enabledConsumerRequiresBothCredentialsBeforeStarting() {
        LedgerConfiguration config = mock(LedgerConfiguration.class);
        LedgerConfiguration.Database database = mock(LedgerConfiguration.Database.class);
        LedgerConfiguration.Broker broker = mock(LedgerConfiguration.Broker.class);
        when(config.enabled()).thenReturn(true);
        when(config.database()).thenReturn(database);
        when(config.broker()).thenReturn(broker);
        when(database.password()).thenReturn(Optional.empty());
        when(broker.password()).thenReturn(Optional.empty());
        assertEquals("Ledger is enabled but shardshop.ledger.database.password is not set",
                assertThrows(IllegalArgumentException.class, () -> new LedgerWiring().store(config)).getMessage());
        when(database.password()).thenReturn(Optional.of("private-password"));
        assertEquals("Ledger is enabled but shardshop.ledger.broker.password is not set",
                assertThrows(IllegalArgumentException.class, () -> new LedgerWiring().store(config)).getMessage());
    }
}
