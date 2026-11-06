package dev.nklip.javacraft.shardshop.testing.cluster.recovery;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    @Test void recoveryOutputsCannotContainOrOverwriteTheirInput() {
        assertThrows(IllegalArgumentException.class, () -> Recovery.separate(Path.of("/tmp/evidence"), Path.of("/tmp/evidence/input")));
        assertThrows(IllegalArgumentException.class, () -> Recovery.separate(Path.of("/tmp/evidence/output"), Path.of("/tmp/evidence")));
        assertDoesNotThrow(() -> Recovery.separate(Path.of("/tmp/output"), Path.of("/tmp/input")));
    }
    @Test void selectedRecoveryIdentityRejectsNoncanonicalOrOutOfRangeInput() {
        String key="shardshop.recovery.order-id", before=System.getProperty(key);
        try {
            for(String invalid:new String[]{"0","-1","01","1; DROP TABLE orders","9223372036854775808"}) {
                System.setProperty(key,invalid); assertThrows(IllegalArgumentException.class,Recovery::selectedOrder);
            }
            System.setProperty(key,"9223372036854775807"); assertEquals(Long.MAX_VALUE,Recovery.selectedOrder());
        } finally { if(before==null) System.clearProperty(key); else System.setProperty(key,before); }
    }
}
