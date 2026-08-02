package dev.nklip.javacraft.ewrs.app.controller;

import dev.nklip.javacraft.ewrs.api.shared.ErrorResponse;
import dev.nklip.javacraft.ewrs.app.exception.InvalidWorkRequestTransitionException;
import dev.nklip.javacraft.ewrs.app.exception.UnknownBudgetCodeException;
import dev.nklip.javacraft.ewrs.app.exception.WorkRequestNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates EWRS application exceptions into stable HTTP error payloads.
 * Architecture mapping: final controller-layer step after Write Side or Read Side failures bubble out of the
 * Runtime Topology.
 */
@RestControllerAdvice
@SuppressWarnings("unused")
public class RestExceptionHandler {

    @ExceptionHandler(WorkRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            WorkRequestNotFoundException exception,
            HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidWorkRequestTransitionException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
            InvalidWorkRequestTransitionException exception,
            HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(UnknownBudgetCodeException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(
            UnknownBudgetCodeException exception,
            HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Validation failed");
        return buildResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        ));
    }
}
