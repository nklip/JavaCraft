package dev.nklip.javacraft.mathparser.gui.resources;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HelpMessageLoaderTest {
    private static final String FALLBACK_MESSAGE = "Help content is unavailable.";

    @Test
    void testLoadHelpMessage() {
        String message = HelpMessageLoader.loadHelpMessage();

        Assertions.assertNotNull(message);
        Assertions.assertFalse(message.isBlank());
        Assertions.assertTrue(message.contains("Functions with one variable:"));
        Assertions.assertTrue(message.contains("Functions with two variables:"));
        Assertions.assertTrue(message.contains("Functions with many variables:"));
    }

    @Test
    void testLoadHelpMessageShouldReturnFallbackWhenResourceIsMissing() {
        @SuppressWarnings("unchecked")
        Supplier<InputStream> resourceSupplier = Mockito.mock(Supplier.class);
        Mockito.when(resourceSupplier.get()).thenReturn(null);

        String message = HelpMessageLoader.loadHelpMessage(resourceSupplier);

        Assertions.assertEquals(FALLBACK_MESSAGE, message);
        Mockito.verify(resourceSupplier).get();
    }

    @Test
    void testLoadHelpMessageShouldReturnFallbackWhenResourceReadFails() throws IOException {
        InputStream brokenInputStream = Mockito.mock(InputStream.class);
        Mockito.when(brokenInputStream.readAllBytes()).thenThrow(new IOException("simulated read failure"));

        String message = HelpMessageLoader.loadHelpMessage(() -> brokenInputStream);

        Assertions.assertEquals(FALLBACK_MESSAGE, message);
        Mockito.verify(brokenInputStream).readAllBytes();
        Mockito.verify(brokenInputStream).close();
    }
}
