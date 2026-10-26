package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryLossFixtureTest {
    @Test
    void retainedLogicalCommandAndOriginalTransportIdsAreDifferentAndBothValidated() {
        var witness = witness("shard-a");
        RecoveryLossFixture.validateWitnesses("shard-a", List.of(witness));
        assertNotEquals(witness.commandId(), witness.commandEnvelope().path("messageId").asLong());
        ((ObjectNode) witness.commandEnvelope()).put("commandId", "999");
        assertThrows(IllegalArgumentException.class, () -> RecoveryLossFixture.validateWitnesses("shard-a", List.of(witness)));
    }

    @Test
    void missingOrChangedAcknowledgedResultEnvelopeCannotQualifyAsAConfirmedWitness() {
        var rejected = witness("shard-a");
        ((ObjectNode) rejected.resultEnvelope()).put("outcome", "REJECTED");
        assertThrows(IllegalArgumentException.class, () -> RecoveryLossFixture.validateWitnesses("shard-a", List.of(rejected)));
        var witness = witness("shard-a");
        ((ObjectNode) witness.resultEnvelope()).put("requestFingerprint", "changed");
        var changed = witness;
        assertThrows(IllegalArgumentException.class, () -> RecoveryLossFixture.validateWitnesses("shard-a", List.of(changed)));
    }

    @Test
    void lossRefusesLedgerForeignShardAndDuplicateOrdersBeforeContactingAnyTarget() {
        OwnedRestoreTarget target = mock(OwnedRestoreTarget.class);
        var original = witness("shard-a");
        assertThrows(IllegalArgumentException.class, () -> RecoveryLossFixture.createOlderDump(target, "ledger-db", List.of(original), Path.of("/unused")));
        assertThrows(IllegalArgumentException.class, () -> RecoveryLossFixture.createOlderDump(target, "shard-b", List.of(original), Path.of("/unused")));
        assertThrows(IllegalArgumentException.class, () -> RecoveryLossFixture.createOlderDump(target, "shard-a", List.of(original,original), Path.of("/unused")));
        assertThrows(IllegalArgumentException.class, () -> RecoveryLossFixture.createOlderDump(target, "shard-a", List.of(original), Path.of("relative")));
        verifyNoInteractions(target);
    }

    @Test
    void witnessCountIsBoundedBeforeOpeningConnections() {
        OwnedRestoreTarget target = mock(OwnedRestoreTarget.class);
        assertThrows(IllegalArgumentException.class, () -> RecoveryLossFixture.select(target, 0));
        assertThrows(IllegalArgumentException.class, () -> RecoveryLossFixture.select(target, 21));
        verifyNoInteractions(target);
    }

    private static RecoveryLossFixture.Witness witness(String shard) {
        return new RecoveryLossFixture.Witness(shard, 1, 2, 3, 4, 6,
                KindLab.parse("""
                        {"messageId":"5","resultMessageId":"6","commandId":"4","orderId":"1",
                         "buyerId":"2","sagaId":"3","requestFingerprint":"original"}
                        """),
                KindLab.parse("""
                        {"messageId":"6","commandId":"4","orderId":"1","buyerId":"2",
                         "sagaId":"3","requestFingerprint":"original","outcome":"RECORDED"}
                        """));
    }
}
