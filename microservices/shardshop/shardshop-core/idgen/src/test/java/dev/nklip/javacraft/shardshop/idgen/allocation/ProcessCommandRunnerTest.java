package dev.nklip.javacraft.shardshop.idgen.allocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProcessCommandRunnerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final List<String> COMMAND = List.of("kubectl", "get", "configmap");
    private final ProcessCommandRunner.ProcessStarter starter = mock(ProcessCommandRunner.ProcessStarter.class);
    private final ProcessCommandRunner.OutputReader reader = mock(ProcessCommandRunner.OutputReader.class);
    private final LongSupplier clock = mock(LongSupplier.class);
    private final Process process = mock(Process.class);
    @SuppressWarnings("unchecked")
    private final Future<byte[]> output = mock(Future.class);
    private final ProcessCommandRunner runner = new ProcessCommandRunner(starter, reader, clock);

    @ParameterizedTest
    @MethodSource("startupFailures")
    void failedKubectlStartIsActionableWithoutExposingExternalErrors(Exception externalFailure) throws Exception {
        when(starter.start(COMMAND)).thenThrow(externalFailure);

        IOException failure = assertThrows(IOException.class, () -> runner.run(COMMAND, TIMEOUT));

        assertEquals("kubectl could not be started; install it on PATH and check executable permissions.", failure.getMessage());
        assertNull(failure.getCause());
        verifyNoInteractions(reader, process);
    }

    @Test
    void launchesWithoutAShellAndDiscardsStderr() throws Exception {
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("registry".getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            assertEquals(List.of(COMMAND), context.arguments());
            when(builder.redirectError(ProcessBuilder.Redirect.DISCARD)).thenReturn(builder);
            when(builder.start()).thenReturn(process);
        })) {
            assertEquals(new CommandResult(0, "registry"), new ProcessCommandRunner().run(COMMAND, TIMEOUT));
            assertEquals(1, builders.constructed().size());
            verify(builders.constructed().getFirst()).redirectError(ProcessBuilder.Redirect.DISCARD);
            verify(process).destroyForcibly();
        }
    }

    @Test
    void boundsOutputAndUsesOnlyTheRemainingBudget() throws Exception {
        prepare();
        when(clock.getAsLong()).thenReturn(50L, 150L, 250L);
        when(process.waitFor(999_999_900L, TimeUnit.NANOSECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(7);
        when(output.get(999_999_800L, TimeUnit.NANOSECONDS)).thenReturn(new byte[4096]);
        InputStream stream = mock(InputStream.class);
        byte[] bounded = new byte[4097];
        when(process.getInputStream()).thenReturn(stream);
        when(stream.readNBytes(4097)).thenReturn(bounded);
        when(reader.start(any())).thenAnswer(invocation -> {
            Callable<byte[]> task = invocation.getArgument(0);
            assertArrayEquals(bounded, task.call());
            return output;
        });

        assertEquals(new CommandResult(7, "\0".repeat(4096)), runner.run(COMMAND, TIMEOUT));
        verify(stream).close();
        verify(output).cancel(true);
        verify(process).destroyForcibly();
    }

    @Test
    void closesTheOutputStreamWhenReadingFails() throws Exception {
        prepare();
        InputStream stream = mock(InputStream.class);
        when(process.getInputStream()).thenReturn(stream);
        when(stream.readNBytes(4097)).thenThrow(new IOException("read failed"));
        when(reader.start(any())).thenAnswer(invocation -> {
            Callable<byte[]> task = invocation.getArgument(0);
            assertEquals("read failed", assertThrows(IOException.class, task::call).getMessage());
            return output;
        });

        assertEquals("kubectl process timed out", assertThrows(IOException.class,
                () -> runner.run(COMMAND, TIMEOUT)).getMessage());
        verify(stream).close();
        verify(process).destroyForcibly();
    }

    @Test
    void killsATimedOutProcessAndCancelsOutputReading() throws Exception {
        prepare();

        assertEquals("kubectl process timed out", assertThrows(IOException.class,
                () -> runner.run(COMMAND, TIMEOUT)).getMessage());
        verify(output).cancel(true);
        verify(process).destroyForcibly();
    }

    @Test
    void rejectsOversizedOutput() throws Exception {
        prepare();
        when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
        when(output.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(new byte[4097]);

        assertEquals("kubectl output exceeds the limit", assertThrows(IOException.class,
                () -> runner.run(COMMAND, TIMEOUT)).getMessage());
        verify(output).cancel(true);
        verify(process).destroyForcibly();
    }

    @Test
    void sanitizesOutputFailuresAndOutputTimeouts() throws Exception {
        prepare();
        when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
        when(output.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new ExecutionException(new IOException("sensitive content")))
                .thenThrow(new TimeoutException("sensitive content"));

        IOException failed = assertThrows(IOException.class, () -> runner.run(COMMAND, TIMEOUT));
        assertEquals("kubectl output could not be read within the deadline", failed.getMessage());
        assertNull(failed.getCause());
        assertEquals(failed.getMessage(), assertThrows(IOException.class,
                () -> runner.run(COMMAND, TIMEOUT)).getMessage());
    }

    @Test
    void propagatesInterruptionAndStillKillsTheProcess() throws Exception {
        prepare();
        when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException("interrupted"));

        assertEquals("interrupted", assertThrows(InterruptedException.class,
                () -> runner.run(COMMAND, TIMEOUT)).getMessage());
        verify(output).cancel(true);
        verify(process).destroyForcibly();
    }

    @Test
    void rejectsAnElapsedDeadlineBeforeWaiting() throws Exception {
        prepare();
        when(clock.getAsLong()).thenReturn(0L, TIMEOUT.toNanos());

        assertEquals("kubectl process deadline expired", assertThrows(IOException.class,
                () -> runner.run(COMMAND, TIMEOUT)).getMessage());
        verify(process).destroyForcibly();
    }

    @ParameterizedTest
    @MethodSource("invalidTimeouts")
    void rejectsInvalidTimeoutsBeforeStartingAProcess(Duration timeout) {
        assertNotNull(assertThrows(IllegalArgumentException.class, () -> runner.run(COMMAND, timeout)).getMessage());
        verifyNoInteractions(starter, reader, clock);
    }

    @Test
    void rejectsMissingDependenciesAndTimeout() {
        assertEquals("starter", assertThrows(NullPointerException.class,
                () -> new ProcessCommandRunner(null, reader, clock)).getMessage());
        assertEquals("reader", assertThrows(NullPointerException.class,
                () -> new ProcessCommandRunner(starter, null, clock)).getMessage());
        assertEquals("nanoClock", assertThrows(NullPointerException.class,
                () -> new ProcessCommandRunner(starter, reader, null)).getMessage());
        assertEquals("timeout", assertThrows(NullPointerException.class,
                () -> runner.run(COMMAND, null)).getMessage());
    }

    private void prepare() throws IOException {
        when(starter.start(COMMAND)).thenReturn(process);
        when(reader.start(any())).thenReturn(output);
    }

    private static Stream<Duration> invalidTimeouts() {
        return Stream.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE));
    }

    private static Stream<Exception> startupFailures() {
        return Stream.of(new IOException("secret executable path"), new SecurityException("secret permission details"));
    }
}
