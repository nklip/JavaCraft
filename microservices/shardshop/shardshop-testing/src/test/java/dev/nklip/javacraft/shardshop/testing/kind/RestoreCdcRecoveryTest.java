package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestoreCdcRecoveryTest {
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test void fixturePreflightFailsBeforeAnyContainerAndAcceptsPreparedInputs(@TempDir Path directory) throws Exception {
        Path allocation=directory.resolve("generator-allocation.yaml");
        Path order=directory.resolve("order.jar"), ledger=directory.resolve("ledger.jar");
        IOException missing=assertThrows(IOException.class,()->RestoreCdcRecovery.preflight(allocation,order,ledger));
        assertTrue(missing.getMessage().contains("helm template shardshop"));
        Files.writeString(allocation,"kind: ValidatingAdmissionPolicy\n");
        Files.writeString(directory.resolve("rabbitmq.yaml"), """
                kind: StatefulSet
                spec:
                  template:
                    spec:
                      containers:
                        - command: [sh, -c, start]
                          env: []
                          readinessProbe:
                            exec:
                              command: [rabbitmq-diagnostics, ping]
                """);
        Files.createFile(order); Files.createFile(ledger);
        RestoreCdcRecovery.preflight(allocation,order,ledger);
        Files.delete(ledger);
        assertThrows(IOException.class,()->RestoreCdcRecovery.preflight(allocation,order,ledger));
    }

    @Test void resnapshotRequiresRestoredHistoryOffsetsAndNoSlot() throws Exception {
        RestoreCdcRecovery.validateResetState(1,1,0);
        assertThrows(IOException.class,()->RestoreCdcRecovery.validateResetState(0,1,0));
        assertThrows(IOException.class,()->RestoreCdcRecovery.validateResetState(1,0,0));
        assertThrows(IOException.class,()->RestoreCdcRecovery.validateResetState(1,1,1));
        assertThrows(IOException.class,()->RestoreCdcRecovery.validateResetState(1,2,0));
    }

    @Test void incompleteOrAmbiguousSnapshotsCannotAuthorizeRecoveredTraffic() throws Exception {
        for(String offset:List.of("{}","{\"lsn\":90,\"snapshot\":true}","{\"lsn\":90,\"snapshot\":\"last\"}",
                "{\"lsn\":90,\"snapshot_completed\":false}","{\"lsn\":\"90\"}","{\"lsn\":-1}")) {
            assertEquals(0,RestoreCdcRecovery.streamingPosition(JSON.readTree(offset)),offset);
        }
        assertEquals(80,RestoreCdcRecovery.streamingPosition(JSON.readTree("{\"lsn\":90,\"lsn_commit\":80,\"snapshot\":false}")));
        assertEquals(90,RestoreCdcRecovery.streamingPosition(JSON.readTree("{\"lsn\":90,\"snapshot\":\"false\",\"snapshot_completed\":true}")));
    }

    @Test void restoredLedgerAllowsTheSupportedCountriesCurrencies() {
        OwnedRestoreTarget target=mock(OwnedRestoreTarget.class);
        when(target.rootCertificate()).thenReturn(Path.of("/private/tmp/restore/ca.crt"));
        when(target.jdbcUrl("ledger-db")).thenReturn("jdbc:postgresql://localhost:1234/ledger_db?sslmode=verify-full");
        when(target.rolePassword("ledger_app")).thenReturn("isolated-secret");
        RabbitMqBroker broker=mock(RabbitMqBroker.class);
        when(broker.port()).thenReturn(5672);
        when(broker.ledgerPassword()).thenReturn("isolated-broker-secret");
        var config=RestoreCdcRecovery.ledgerProperties(target,broker);
        Set<String> currencies=Set.of(config.getProperty("shardshop.ledger.allowed-currencies").split(","));
        assertEquals(Set.of("CNY","EUR","GBP","JPY","KRW","USD","VND"),currencies);
    }

    @Test void isolatedSagaEnablesOnlyCdcAndUsesVerifiedTargetConnections() {
        OwnedRestoreTarget target=mock(OwnedRestoreTarget.class);
        when(target.rootCertificate()).thenReturn(Path.of("/private/tmp/restore/ca.crt"));
        when(target.rolePassword(anyString())).thenReturn("isolated-secret");
        when(target.jdbcUrl("shard-a")).thenReturn("jdbc:postgresql://localhost:1234/shard_a?sslmode=verify-full");
        var config=RestoreCdcRecovery.orderProperties(target,List.of("shard-a"),5672,"private-broker-secret");
        assertEquals("saga",config.getProperty("shardshop.order.process"));
        assertEquals("true",config.getProperty("shardshop.order.cdc.enabled"));
        for(String worker:List.of("stock","profit","reconciliation","results","cleanup"))
            assertEquals("false",config.getProperty("shardshop.order."+worker+".enabled"));
        assertTrue(config.getProperty("shardshop.order.connections.shard-a.primary-url").contains("sslmode=verify-full"));
        assertEquals("/private/tmp/restore/ca.crt",config.getProperty("shardshop.order.connections.shard-a.root-certificate"));
        assertEquals("ordering_cdc",config.getProperty("shardshop.order.cdc.connections.shard-a.username"));
        assertNull(config.getProperty("shardshop.id.registry-uid"));
    }
}
