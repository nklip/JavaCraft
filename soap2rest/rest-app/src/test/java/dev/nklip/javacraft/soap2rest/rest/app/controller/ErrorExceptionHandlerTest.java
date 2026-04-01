package dev.nklip.javacraft.soap2rest.rest.app.controller;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

public class ErrorExceptionHandlerTest {

    @Test
    public void testHandleBadRequestErrorForIllegalArgumentException() {
        IllegalArgumentException exception = new IllegalArgumentException("Meter is not linked to account.");
        ErrorExceptionHandler errorExceptionHandler = new ErrorExceptionHandler();

        var response = errorExceptionHandler.handleBadRequestError(exception);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals("Meter is not linked to account.", response.getBody());
        Assertions.assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    public void testHandleBadRequestErrorForMalformedPayload() {
        HttpMessageNotReadableException exception =
                new HttpMessageNotReadableException(
                        "Malformed request body",
                        new MockHttpInputMessage(new byte[0])
                );
        ErrorExceptionHandler errorExceptionHandler = new ErrorExceptionHandler();

        var response = errorExceptionHandler.handleBadRequestError(exception);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals("Malformed request body", response.getBody());
        Assertions.assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    public void testHandleInternalServerError() {
        RuntimeException exception = new RuntimeException("Error Message - Runtime error!!!");
        ErrorExceptionHandler errorExceptionHandler = new ErrorExceptionHandler();

        var response = errorExceptionHandler.handleServerError(exception);

        Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Assertions.assertEquals("Error Message - Runtime error!!!", response.getBody());
        Assertions.assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }
}
