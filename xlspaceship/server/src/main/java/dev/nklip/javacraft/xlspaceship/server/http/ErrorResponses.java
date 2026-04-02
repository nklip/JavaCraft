package dev.nklip.javacraft.xlspaceship.server.http;

import dev.nklip.javacraft.xlspaceship.engine.model.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static ResponseEntity<ErrorResponse> jsonError(String message, HttpStatus status) {
        ErrorResponse error = new ErrorResponse();
        error.setError(message);
        return new ResponseEntity<>(error, status);
    }
}
