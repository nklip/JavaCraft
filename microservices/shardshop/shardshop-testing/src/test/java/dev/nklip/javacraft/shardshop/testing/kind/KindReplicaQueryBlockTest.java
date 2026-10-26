package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

class KindReplicaQueryBlockTest {
    @Test
    void primaryPredicateRequiresTheNamedApplicationButStandbyPredicateUsesRecoveryLocks() {
        String primary = KindReplicaQueryBlock.lockSql("probe-123", true);
        String standby = KindReplicaQueryBlock.lockSql("probe-123", false);
        assertTrue(primary.contains("application_name='probe-123'"));
        assertFalse(standby.contains("application_name='probe-123'"));
        assertTrue(primary.contains("AccessExclusiveLock"));
        assertTrue(standby.contains("current_database()"));
        assertTrue(primary.contains("catalog.products'::regclass"));
    }

    @Test
    void preexistingLockIsRejectedBeforeGuardRegistrationOrBackgroundLaunch() throws Exception {
        KindLab lab = mock(KindLab.class);
        KindTopology topology = mock(KindTopology.class);
        KindDrillGuard guard = mock(KindDrillGuard.class);
        KindTopology.Shard shard = shard();
        when(topology.sql(anyString(), anyString())).thenReturn("1");

        assertThrows(IOException.class, () -> new KindReplicaQueryBlock(lab, topology, shard, guard).start());
        verifyNoInteractions(guard);
        verify(lab, never()).kubectlAttempt(any(Duration.class), any(String[].class));
    }

    @Test
    void cleanupCallbackIsRegisteredBeforeLaunchAndScopesTerminationToNamedBackend() throws Exception {
        KindLab lab = mock(KindLab.class);
        KindTopology topology = mock(KindTopology.class);
        KindDrillGuard guard = mock(KindDrillGuard.class);
        KindTopology.Shard shard = shard();
        CountDownLatch command = new CountDownLatch(1);
        CountDownLatch launched = new CountDownLatch(1);
        final KindLab.Probe[] recovery = new KindLab.Probe[1];
        when(topology.sql(anyString(), anyString())).thenReturn("0", "0", "0", "1", "1", "1");
        when(lab.kubectlAttempt(any(Duration.class), any(String[].class))).thenAnswer(invocation -> {
            assertNotNull(recovery[0], "Recovery must register before the remote command starts");
            launched.countDown();
            assertTrue(command.await(10, java.util.concurrent.TimeUnit.SECONDS), "Cleanup did not stop the mock command");
            return new KindLab.CommandResult(0, "", "");
        });
        doAnswer(invocation -> { recovery[0] = invocation.getArgument(1); return null; })
                .when(guard).onClose(anyString(), any());

        KindReplicaQueryBlock block = new KindReplicaQueryBlock(lab, topology, shard, guard);
        try {
            block.start();
            assertTrue(launched.await(10, java.util.concurrent.TimeUnit.SECONDS), "Background command did not start");
            recovery[0].check();
            recovery[0].check();
        } finally {
            command.countDown();
            block.close();
        }

        verify(topology, times(2)).sql(eq("shard-a-0"), argThat(sql -> sql.contains("pg_terminate_backend(pid)") && sql.contains("application_name='shardshop-replica-block-")));
        verify(topology, atLeastOnce()).awaitSql(eq("shard-a-0"), contains("AccessExclusiveLock"), eq("0"));
    }

    private static KindTopology.Shard shard() {
        return new KindTopology.Shard("shard-a", "shard-a-0", List.of("shard-a-1", "shard-a-2"), Map.of());
    }
}
