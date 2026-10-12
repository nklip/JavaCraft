package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP/1.1 exchanges on one socket each. The scenarios control each header line and the body framing, for example a
 * repeated header, an empty chunked body or a body that stops arriving, which an HTTP client library does not send.
 */
public final class RawHttp {
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_BODY_BYTES = 1 << 20;

    private RawHttp() {
    }

    public record Header(String name, String value) {
    }

    public enum Framing { NONE, CONTENT_LENGTH, CHUNKED }

    public record Response(int status, Map<String, List<String>> headers, byte[] body, Duration elapsed) {
        public Optional<String> header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()).stream().findFirst();
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Sends one request with {@code Connection: close} and reads the complete response. */
    public static Response exchange(int port, String method, String target, List<Header> headers, byte[] body,
                                    Framing framing) throws IOException {
        long started = System.nanoTime();
        try (Socket socket = connect(port)) {
            OutputStream output = socket.getOutputStream();
            output.write(head(port, method, target, headers, body.length, framing));
            output.write(framing == Framing.CHUNKED ? chunked(body) : body);
            output.flush();
            return read(socket.getInputStream(), started);
        }
    }

    /**
     * Sends the head of a request with a Content-Length body and only the first {@code sentBytes} bytes of the body.
     * The caller owns the open connection.
     */
    public static Socket send(int port, String method, String target, List<Header> headers, byte[] body, int sentBytes)
            throws IOException {
        Socket socket = connect(port);
        OutputStream output = socket.getOutputStream();
        output.write(head(port, method, target, headers, body.length, Framing.CONTENT_LENGTH));
        output.write(body, 0, sentBytes);
        output.flush();
        return socket;
    }

    /** Reads the response of a request that {@link #send} started at the given {@link System#nanoTime()}. */
    public static Response read(Socket socket, long started) throws IOException {
        return read(socket.getInputStream(), started);
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            return socket;
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
    }

    private static byte[] head(int port, String method, String target, List<Header> headers, int bodyLength,
                               Framing framing) {
        StringBuilder head = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n");
        for (Header header : headers) {
            head.append(header.name()).append(": ").append(header.value()).append("\r\n");
        }
        if (framing == Framing.CONTENT_LENGTH) {
            head.append("Content-Length: ").append(bodyLength).append("\r\n");
        } else if (framing == Framing.CHUNKED) {
            head.append("Transfer-Encoding: chunked\r\n");
        }
        return head.append("Connection: close\r\n\r\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] chunked(byte[] body) {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        if (body.length > 0) {
            chunks.writeBytes((Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            chunks.writeBytes(body);
            chunks.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        chunks.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return chunks.toByteArray();
    }

    private static Response read(InputStream socketInput, long started) throws IOException {
        BufferedInputStream input = new BufferedInputStream(socketInput);
        String statusLine = line(input);
        String[] status = statusLine.split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/1.")) {
            throw new IOException("Invalid HTTP status line: " + statusLine);
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String line = line(input); !line.isEmpty(); line = line(input)) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("Invalid HTTP header line: " + line);
            }
            headers.computeIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                    .add(line.substring(colon + 1).trim());
        }
        Response head = new Response(Integer.parseInt(status[1]), headers, new byte[0], Duration.ZERO);
        byte[] body;
        if (head.header("Transfer-Encoding").map(value -> value.equalsIgnoreCase("chunked")).orElse(false)) {
            body = dechunk(input);
        } else if (head.header("Content-Length").isPresent()) {
            int length = Integer.parseInt(head.header("Content-Length").orElseThrow());
            body = input.readNBytes(length);
            if (body.length != length) {
                throw new IOException("The response body ended after " + body.length + " of " + length + " bytes");
            }
        } else {
            body = input.readNBytes(MAX_BODY_BYTES);
        }
        return new Response(head.status(), headers, body, Duration.ofNanos(System.nanoTime() - started));
    }

    private static byte[] dechunk(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String size = line(input);
            int semicolon = size.indexOf(';');
            int length = Integer.parseInt((semicolon < 0 ? size : size.substring(0, semicolon)).trim(), 16);
            if (length == 0) {
                while (!line(input).isEmpty()) {
                    // Skip the trailer fields.
                }
                return body.toByteArray();
            }
            if (body.size() + length > MAX_BODY_BYTES) {
                throw new IOException("The response body is larger than " + MAX_BODY_BYTES + " bytes");
            }
            body.writeBytes(input.readNBytes(length));
            line(input);
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int next = input.read(); next != '\n'; next = input.read()) {
            if (next < 0) {
                throw new IOException("The connection closed before the end of the HTTP head");
            }
            if (next != '\r') {
                line.write(next);
            }
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }
}
