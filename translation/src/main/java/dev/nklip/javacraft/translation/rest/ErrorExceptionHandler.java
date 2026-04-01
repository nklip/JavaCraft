package dev.nklip.javacraft.translation.rest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class ErrorExceptionHandler {

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<String> handleError(Throwable ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return ResponseEntity.internalServerError().headers(headers).body(ex.getMessage());
    }

}
