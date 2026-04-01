package my.javacraft.soap2rest.rest.app.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@Slf4j
@ControllerAdvice
public class ErrorExceptionHandler {

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<String> handleBadRequestError(Exception ex) {
        log.warn(ex.getMessage(), ex);
        return buildResponse(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<String> handleServerError(Throwable ex) {
        log.error(ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }

    private ResponseEntity<String> buildResponse(HttpStatus status, Throwable ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return ResponseEntity.status(status).headers(headers).body(ex.getMessage());
    }
}
