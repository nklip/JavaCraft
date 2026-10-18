package dev.nklip.javacraft.shardshop.ledger.config;

import dev.nklip.javacraft.shardshop.ledger.command.LedgerService;
import dev.nklip.javacraft.shardshop.ledger.persistence.JdbcLedgerStore;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
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

    /** shardshop.ledger.enabled=false disables both workers, also when the publisher keeps its default switch. */
    @Test
    void disabledLedgerStartsNeitherWorker() throws Exception {
        LedgerConfiguration config = mock(LedgerConfiguration.class);
        LedgerConfiguration.Broker broker = mock(LedgerConfiguration.Broker.class);
        LedgerConfiguration.Publisher publisher = mock(LedgerConfiguration.Publisher.class);
        JdbcLedgerStore store = mock(JdbcLedgerStore.class);
        try (ServerSocket rabbit = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            when(config.enabled()).thenReturn(false);
            when(config.broker()).thenReturn(broker);
            when(config.publisher()).thenReturn(publisher);
            when(broker.host()).thenReturn(InetAddress.getLoopbackAddress().getHostAddress());
            when(broker.port()).thenReturn(rabbit.getLocalPort());
            when(broker.virtualHost()).thenReturn("shardshop");
            when(broker.username()).thenReturn("shardshop-ledger");
            when(broker.password()).thenReturn(Optional.empty());
            when(broker.timeout()).thenReturn(Duration.ofSeconds(1));
            when(publisher.enabled()).thenReturn(true);
            when(publisher.batchSize()).thenReturn(100);
            when(publisher.pollInterval()).thenReturn(Duration.ofMillis(10));
            var wiring = new LedgerWiring();
            try (var consumer = wiring.consumer(mock(LedgerService.class), config);
                 var relay = wiring.relay(store, config)) {
                consumer.start();
                relay.start();
                rabbit.setSoTimeout(300);
                assertThrows(SocketTimeoutException.class, rabbit::accept, "A disabled consumer connected to RabbitMQ");
            }
            verifyNoInteractions(store);
        }
    }
}
