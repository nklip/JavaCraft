package dev.nklip.javacraft.vfs.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ClientTest {

    @Test
    void testConstructorInitializesDependencies() {
        Client client = new Client();

        Assertions.assertNotNull(client.userManager);
        Assertions.assertNotNull(client.messageSender);
        Assertions.assertNotNull(client.networkManager);
    }

    @Test
    void testRunHandlesExitCommand() {
        Client client = new Client();
        byte[] input = "exit\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = System.in;
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));
        System.setIn(new ByteArrayInputStream(input));
        try {
            client.run();
        } finally {
            System.setOut(originalOut);
            System.setIn(originalIn);
        }

        String output = outContent.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(output.contains("VFS client is running."));
        Assertions.assertTrue(output.contains("Successful exit!"));
    }

    @Test
    void testRunHandlesReadIOExceptionAndContinuesToExitCommand() {
        Client client = new Client();
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        java.io.InputStream originalIn = System.in;
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errContent, true, StandardCharsets.UTF_8));
        System.setIn(new FailOnceThenExitInputStream());
        try {
            client.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.setIn(originalIn);
        }

        String output = outContent.toString(StandardCharsets.UTF_8);
        String errorOutput = errContent.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(errorOutput.contains("forced-read-error"));
        Assertions.assertTrue(output.contains("Successful exit!"));
    }

    @Test
    void testRunHandlesUnexpectedException() {
        Client client = new Client();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        java.io.InputStream originalIn = System.in;
        System.setErr(new PrintStream(errContent, true, StandardCharsets.UTF_8));
        System.setIn(new ByteArrayInputStream(new byte[0]));
        try {
            client.run();
        } finally {
            System.setErr(originalErr);
            System.setIn(originalIn);
        }

        String errorOutput = errContent.toString(StandardCharsets.UTF_8);
        Assertions.assertFalse(errorOutput.isEmpty());
    }

    private static final class FailOnceThenExitInputStream extends InputStream {
        private final byte[] exitCommandBytes = "exit\n".getBytes(StandardCharsets.UTF_8);
        private boolean firstReadFailed;
        private int pointer;

        @Override
        public int read() throws IOException {
            if (!firstReadFailed) {
                firstReadFailed = true;
                throw new IOException("forced-read-error");
            }
            if (pointer >= exitCommandBytes.length) {
                return -1;
            }
            return exitCommandBytes[pointer++] & 0xFF;
        }
    }
}
