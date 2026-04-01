package dev.nklip.javacraft.elastic.app.rest;

import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public class ErrorExceptionHandlerTest {

    @Test
    public void testHandleBadRequest() {
        ConstraintViolationException exception = new ConstraintViolationException(Set.of());

        ErrorExceptionHandler errorExceptionHandler = new ErrorExceptionHandler();
        ResponseEntity<String> response = errorExceptionHandler.handleBadRequest(exception);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals("Invalid request.", response.getBody());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    public void testHandleIoError() {
        IOException exception = new IOException("socket timeout");

        ErrorExceptionHandler errorExceptionHandler = new ErrorExceptionHandler();
        ResponseEntity<String> response = errorExceptionHandler.handleIoError(exception);

        Assertions.assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Assertions.assertEquals("Service temporarily unavailable.", response.getBody());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    public void testHandleRuntimeException() {
        RuntimeException exception = new RuntimeException("Error Message - Runtime error!!!");

        ErrorExceptionHandler errorExceptionHandler = new ErrorExceptionHandler();
        ResponseEntity<String> response = errorExceptionHandler.handleError(exception);

        Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Assertions.assertEquals("Internal server error.", response.getBody());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    }
}
