package dev.nklip.javacraft.translation.rest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ErrorExceptionHandlerTest {

    @Test
    public void testHandleError() {
        RuntimeException exception = new RuntimeException("Error Message - Runtime error!!!");

        ErrorExceptionHandler errorExceptionHandler = new ErrorExceptionHandler();
        Assertions.assertEquals("""
                        <500 INTERNAL_SERVER_ERROR Internal Server Error,Error Message - Runtime error!!!,[Content-Type:"application/json"]>""",
                errorExceptionHandler.handleError(exception).toString()
        );
    }

}
