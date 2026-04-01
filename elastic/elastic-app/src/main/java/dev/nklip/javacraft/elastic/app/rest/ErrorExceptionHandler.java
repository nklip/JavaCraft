package dev.nklip.javacraft.elastic.app.rest;

import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@ControllerAdvice
public class ErrorExceptionHandler {

    private static final String BAD_REQUEST_MESSAGE = "Invalid request.";
    private static final String IO_ERROR_MESSAGE = "Service temporarily unavailable.";
    private static final String INTERNAL_ERROR_MESSAGE = "Internal server error.";

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<String> handleBadRequest(Exception ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, BAD_REQUEST_MESSAGE);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> handleIoError(IOException ex) {
        log.error("I/O error while handling request", ex);
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, IO_ERROR_MESSAGE);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<String> handleError(Throwable ex) {
        log.error("Unhandled server error", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MESSAGE);
    }

    private ResponseEntity<String> buildResponse(HttpStatus status, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return ResponseEntity.status(status).headers(headers).body(message);
    }

}
