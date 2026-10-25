package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashBreakpointTest {
    private static final String ACK = "channel.basicAck(deliveryTag, false);";
    @TempDir Path directory;

    @Test
    void findsAnchorAfterHarmlessLineShift() throws Exception {
        Path source = source(ACK + "\n");
        assertEquals(1, CrashBreakpoint.resolveSourceLine(source, ACK));
        Files.writeString(source, "// added documentation\n\n    " + ACK + "\n");
        assertEquals(3, CrashBreakpoint.resolveSourceLine(source, ACK));
    }

    @Test
    void refusesMissingAnchor() throws Exception {
        IOException failure = assertThrows(IOException.class, () ->
                CrashBreakpoint.resolveSourceLine(source("return;\n"), ACK));
        assertTrue(failure.getMessage().contains("Missing crash source anchor"));
    }

    @Test
    void refusesAmbiguousAnchor() throws Exception {
        IOException failure = assertThrows(IOException.class, () ->
                CrashBreakpoint.resolveSourceLine(source(ACK + "\n" + ACK + "\n"), ACK));
        assertTrue(failure.getMessage().contains("Ambiguous crash source anchor"));
    }

    @Test
    void closingBoundaryFollowsCheckedStatementsAndSurvivesComments() throws Exception {
        Path source = source("if (ready) {\n" + ACK + "\n// acknowledgement completed\n\n}\n}\n}\n");
        assertEquals(7, CrashBreakpoint.resolveSourceLine(source, ACK, "}", "}", "}"));
        Files.writeString(source, ACK + "\notherOperation();\n}\n}\n}\n");
        assertThrows(IOException.class, () -> CrashBreakpoint.resolveSourceLine(source, ACK, "}", "}", "}"));
    }

    @Test
    void requiresCompleteLinesAndNonemptyAnchors() throws Exception {
        Path source = source("// " + ACK + "\nother." + ACK + "\n");
        assertThrows(IOException.class, () -> CrashBreakpoint.resolveSourceLine(source, ACK));
        assertThrows(IOException.class, () -> CrashBreakpoint.resolveSourceLine(source));
        assertThrows(IOException.class, () -> CrashBreakpoint.resolveSourceLine(source, " "));
    }

    private Path source(String content) throws IOException {
        Path file = directory.resolve("Consumer.java");
        Files.writeString(file, content);
        return file;
    }
}
