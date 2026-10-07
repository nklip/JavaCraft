package dev.nklip.javacraft.shardshop.idgen.allocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KubectlRegistryTest {
    private static final String UID = "12345678-1234-5678-9abc-123456789abc";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final RegistryState INITIAL = new RegistryState(UID, "15", 0);
    private final CommandRunner runner = mock(CommandRunner.class);
    private final KubectlRegistry registry = new KubectlRegistry(List.of("kubectl", "--context", "lab"), runner);

    @Test
    void readsTheNamedRegistryWithABoundedRequest() throws Exception {
        when(runner.run(anyList(), eq(TIMEOUT))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            assertEquals(List.of("kubectl", "--context", "lab", "--request-timeout=2000000000ns", "get",
                    "configmap", "shardshop-snowflake-generators", "-o",
                    "go-template={{.metadata.uid}}{{\"\\t\"}}{{.metadata.resourceVersion}}"
                            + "{{\"\\t\"}}{{index .data \"highWaterMark\"}}"), command);
            return new CommandResult(0, state("15", "0"));
        });

        assertEquals(INITIAL, registry.read(TIMEOUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "999", "1023"})
    void acceptsCanonicalHighWaterMarks(String highWaterMark) throws Exception {
        when(runner.run(anyList(), eq(TIMEOUT))).thenReturn(new CommandResult(0, state("0015", highWaterMark)));

        assertEquals(new RegistryState(UID, "0015", Integer.parseInt(highWaterMark)), registry.read(TIMEOUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "\t\t", "x\t15\t0", "12345678-1234-5678-9ABC-123456789ABC\t15\t0",
            "12345678-1234-5678-9abc-123456789abc\t\t0", "12345678-1234-5678-9abc-123456789abc\t\"x\t0",
            "12345678-1234-5678-9abc-123456789abc\t15\t00", "12345678-1234-5678-9abc-123456789abc\t15\t-1",
            "12345678-1234-5678-9abc-123456789abc\t15\t1024", "12345678-1234-5678-9abc-123456789abc\t15\t10000",
            "12345678-1234-5678-9abc-123456789abc\t15\t0\n", "12345678-1234-5678-9abc-123456789abc\t15\t0\tx"})
    void rejectsInvalidRegistryStateWithoutIncludingItInTheError(String state) throws Exception {
        when(runner.run(anyList(), eq(TIMEOUT))).thenReturn(new CommandResult(0, state));

        IOException error = assertThrows(IOException.class, () -> registry.read(TIMEOUT));
        assertEquals("Generator registry could not be read; check registry existence, state, Kubernetes API access and GET permission.", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void suppressesCommandErrorsAndProcessOutput() throws Exception {
        when(runner.run(anyList(), eq(TIMEOUT))).thenReturn(new CommandResult(1, "sensitive output"))
                .thenThrow(new IOException("sensitive failure"));

        assertEquals("Generator registry could not be read; check registry existence, state, Kubernetes API access and GET permission.",
                assertThrows(IOException.class, () -> registry.read(TIMEOUT)).getMessage());
        assertEquals("Generator registry could not be read; check registry existence, state, Kubernetes API access and GET permission.",
                assertThrows(IOException.class, () -> registry.read(TIMEOUT)).getMessage());
    }

    @Test
    void reservesUsingAllThreeAtomicPreconditionsAndCanSkipBurnedSlots() throws Exception {
        when(runner.run(anyList(), eq(TIMEOUT))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            assertEquals("patch", command.get(4));
            assertEquals("--type=json", command.get(7));
            assertEquals("-p", command.get(8));
            assertEquals("[{\"op\":\"test\",\"path\":\"/metadata/uid\",\"value\":\"" + UID
                    + "\"},{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":\"15\"},"
                    + "{\"op\":\"test\",\"path\":\"/data/highWaterMark\",\"value\":\"0\"},"
                    + "{\"op\":\"replace\",\"path\":\"/data/highWaterMark\",\"value\":\"2\"}]", command.get(9));
            return new CommandResult(0, state("16", "2"));
        });

        assertTrue(registry.reserve(INITIAL, 2, TIMEOUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678-1234-5678-9abc-123456789abd\t16\t1",
            "12345678-1234-5678-9abc-123456789abc\t16\t2",
            "12345678-1234-5678-9abc-123456789abc\t15\t1", "malformed response"})
    void doesNotConfirmAnUncertainPatchResponse(String response) throws Exception {
        when(runner.run(anyList(), eq(TIMEOUT))).thenReturn(new CommandResult(0, response));

        assertFalse(registry.reserve(INITIAL, 1, TIMEOUT));
    }

    @Test
    void treatsFailedCommandsAndLostResponsesAsUncertain() throws Exception {
        when(runner.run(anyList(), eq(TIMEOUT))).thenReturn(new CommandResult(1, "conflict"))
                .thenThrow(new IOException("connection lost"));

        assertFalse(registry.reserve(INITIAL, 1, TIMEOUT));
        assertFalse(registry.reserve(INITIAL, 1, TIMEOUT));
    }

    @Test
    void propagatesInterruptionFromReadsAndReservations() throws Exception {
        when(runner.run(anyList(), eq(TIMEOUT))).thenThrow(new InterruptedException("interrupted"));

        assertEquals("interrupted", assertThrows(InterruptedException.class, () -> registry.read(TIMEOUT)).getMessage());
        assertEquals("interrupted", assertThrows(InterruptedException.class,
                () -> registry.reserve(INITIAL, 1, TIMEOUT)).getMessage());
    }

    @Test
    void preservesLocalStartupDiagnosticsFromReadsAndReservations() throws Exception {
        AllocationFailure failure = new AllocationFailure(AllocationFailure.Reason.KUBECTL_START);
        when(runner.run(anyList(), eq(TIMEOUT))).thenThrow(failure);

        assertSame(failure, assertThrows(AllocationFailure.class, () -> registry.read(TIMEOUT)));
        assertSame(failure, assertThrows(AllocationFailure.class, () -> registry.reserve(INITIAL, 1, TIMEOUT)));
    }

    @Test
    void rejectsInvalidReservationCoordinatesBeforeIo() {
        assertNotNull(assertThrows(IllegalArgumentException.class,
                () -> registry.reserve(INITIAL, 0, TIMEOUT)).getMessage());
        assertNotNull(assertThrows(IllegalArgumentException.class,
                () -> registry.reserve(INITIAL, 1024, TIMEOUT)).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> registry.reserve(null, 1, TIMEOUT)).getMessage());
        verifyNoInteractions(runner);
    }

    @Test
    void rejectsInvalidCommandsAndMissingRunner() {
        assertNotNull(assertThrows(IllegalArgumentException.class,
                () -> new KubectlRegistry(List.of(), runner)).getMessage());
        assertNotNull(assertThrows(IllegalArgumentException.class,
                () -> new KubectlRegistry(List.of("kubectl", " "), runner)).getMessage());
        assertEquals("runner", assertThrows(NullPointerException.class,
                () -> new KubectlRegistry(List.of("kubectl"), null)).getMessage());
        assertNotNull(new KubectlRegistry(List.of("kubectl")));
    }

    private static String state(String version, String highWaterMark) {
        return UID + "\t" + version + "\t" + highWaterMark;
    }
}
