package dev.nklip.javacraft.soap2rest.soap.service;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;

class HttpCallServiceTest {

    private static final String AUTH_TOKEN = "57AkjqNuz44QmUHQuvVo";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testPutShouldUseRestClientWithJsonBody() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        startServer("/api/v1/smart/1/gas", exchange -> {
            captureRequest(exchange, method, authHeader, contentType, body);
            writeResponse(exchange, HttpStatus.OK.value(), MediaType.APPLICATION_JSON_VALUE, "true");
        });

        HttpCallService service = new HttpCallService(
                "http://localhost",
                Integer.toString(server.getAddress().getPort()),
                AUTH_TOKEN,
                CONNECT_TIMEOUT,
                READ_TIMEOUT
        );
        Payload payload = new Payload("smart", 2);

        ResponseEntity<Boolean> response = service.put("/api/v1/smart/1/gas", Boolean.class, payload);

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals(Boolean.TRUE, response.getBody());
        Assertions.assertEquals("PUT", method.get());
        Assertions.assertEquals(AUTH_TOKEN, authHeader.get());
        Assertions.assertTrue(contentType.get().startsWith(MediaType.APPLICATION_JSON_VALUE));
        Assertions.assertEquals(objectMapper.writeValueAsString(payload), body.get());
    }

    @Test
    void testGetShouldDeserializeJsonResponse() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();

        startServer("/api/v1/smart/1", exchange -> {
            captureRequest(exchange, method, authHeader, null, null);
            writeResponse(exchange, HttpStatus.OK.value(), MediaType.APPLICATION_JSON_VALUE, """
                    {"status":"ok"}
                    """.trim());
        });

        HttpCallService service = new HttpCallService(
                "http://localhost",
                Integer.toString(server.getAddress().getPort()),
                AUTH_TOKEN,
                CONNECT_TIMEOUT,
                READ_TIMEOUT
        );

        ResponseEntity<StatusResponse> response = service.get("/api/v1/smart/1", StatusResponse.class);

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals("ok", response.getBody().status());
        Assertions.assertEquals("GET", method.get());
        Assertions.assertEquals(AUTH_TOKEN, authHeader.get());
    }

    @Test
    void testDeleteShouldReturnPlainTextBody() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();

        startServer("/api/v1/gas/1", exchange -> {
            captureRequest(exchange, method, authHeader, null, null);
            writeResponse(exchange, HttpStatus.OK.value(), MediaType.TEXT_PLAIN_VALUE, "1");
        });

        HttpCallService service = new HttpCallService(
                "http://localhost",
                Integer.toString(server.getAddress().getPort()),
                AUTH_TOKEN,
                CONNECT_TIMEOUT,
                READ_TIMEOUT
        );

        ResponseEntity<String> response = service.delete("/api/v1/gas/1");

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals("1", response.getBody());
        Assertions.assertEquals("DELETE", method.get());
        Assertions.assertEquals(AUTH_TOKEN, authHeader.get());
    }

    @Test
    void testGetShouldPreserveHttpServerErrorException() throws Exception {
        startServer("/api/v1/smart/async/req-1", exchange ->
                writeResponse(
                        exchange,
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        MediaType.APPLICATION_JSON_VALUE,
                        """
                                {"requestId":"req-1","errorMessage":"boom"}
                                """.trim()
                )
        );

        HttpCallService service = new HttpCallService(
                "http://localhost",
                Integer.toString(server.getAddress().getPort()),
                AUTH_TOKEN,
                CONNECT_TIMEOUT,
                READ_TIMEOUT
        );

        HttpServerErrorException exception = Assertions.assertThrows(
                HttpServerErrorException.class,
                () -> service.get("/api/v1/smart/async/req-1", String.class)
        );

        Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
        Assertions.assertTrue(exception.getResponseBodyAsString().contains("\"errorMessage\":\"boom\""));
    }

    private void startServer(String path, ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
    }

    private void captureRequest(
            HttpExchange exchange,
            AtomicReference<String> method,
            AtomicReference<String> authHeader,
            AtomicReference<String> contentType,
            AtomicReference<String> body
    ) throws IOException {
        method.set(exchange.getRequestMethod());
        authHeader.set(exchange.getRequestHeaders().getFirst(HttpCallService.AUTH_TOKEN_HEADER_NAME));
        if (contentType != null) {
            contentType.set(exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        }
        if (body != null) {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void writeResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private record Payload(String service, int count) {}

    private record StatusResponse(String status) {}

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
