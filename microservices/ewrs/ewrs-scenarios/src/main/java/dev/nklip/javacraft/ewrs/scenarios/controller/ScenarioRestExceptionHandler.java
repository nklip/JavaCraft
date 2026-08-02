package dev.nklip.javacraft.ewrs.scenarios.controller;

import dev.nklip.javacraft.ewrs.api.shared.ErrorResponse;
import dev.nklip.javacraft.ewrs.scenarios.exception.ScenarioExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps scenario-driver failures into a stable HTTP error payload.
 * This keeps Swagger/manual users informed when the target EWRS app is unavailable or a scenario cannot complete.
 */
@RestControllerAdvice
@SuppressWarnings("unused")
public class ScenarioRestExceptionHandler {

    @ExceptionHandler(ScenarioExecutionException.class)
    public ResponseEntity<ErrorResponse> handleScenarioExecutionException(
            ScenarioExecutionException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.BAD_GATEWAY;
        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }
}
