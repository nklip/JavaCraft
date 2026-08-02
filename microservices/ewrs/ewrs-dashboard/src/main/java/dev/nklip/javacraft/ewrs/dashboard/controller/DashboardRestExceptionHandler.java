package dev.nklip.javacraft.ewrs.dashboard.controller;

import dev.nklip.javacraft.ewrs.api.shared.ErrorResponse;
import dev.nklip.javacraft.ewrs.dashboard.exception.DashboardWorkRequestNotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Maps dashboard-specific failures to the shared EWRS error response contract.
 * Architecture mapping: keeps read-side HTTP failures predictable for the dashboard JSON endpoints and Swagger users.
 */
@RestControllerAdvice(assignableTypes = DashboardDataController.class)
public class DashboardRestExceptionHandler {

    @ExceptionHandler(DashboardWorkRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkRequestNotFound(
            DashboardWorkRequestNotFoundException exception,
            ServletWebRequest request
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                exception.getMessage(),
                request.getRequest().getRequestURI()
        ));
    }
}
