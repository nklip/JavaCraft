package dev.nklip.vfs.core.exceptions;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class QuitExceptionTest {

    @Test
    void testQuitExceptionStoresMessage() {
        QuitException exception = new QuitException("bye");

        Assertions.assertEquals("bye", exception.getMessage());
    }

    @Test
    void testQuitExceptionIsRuntimeException() {
        QuitException exception = new QuitException("quit");

        Assertions.assertInstanceOf(RuntimeException.class, exception);
    }
}
