package dev.nklip.javacraft.linker.datamanager.service;

import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SymbolGeneratorServicesTest {

    @Test
    public void testGeneratedSymbolsShouldAlwaysBeAlphaNumeric() {
        for (int i = 0; i < 1000; i++) {
            char symbol = SymbolGeneratorServices.generateSymbol();
            Assertions.assertTrue(Character.isLetterOrDigit(symbol));
        }
    }

    @Test
    public void testGenerateShortTextShouldRespectLength() {
        String generated = SymbolGeneratorServices.generateShortText(12);
        Assertions.assertEquals(12, generated.length());
    }

    @Test
    public void testGenerateShortTextShouldBeDeterministicWithProvidedRandom() {
        Random random = new Random(42L);
        String generated = SymbolGeneratorServices.generateShortText(6, random);
        Assertions.assertEquals(6, generated.length());
        Assertions.assertEquals("Gpi2C7", generated);
    }

    @Test
    public void testGenerateShortTextShouldRejectNonPositiveLength() {
        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SymbolGeneratorServices.generateShortText(0)
        );
        Assertions.assertEquals("length must be positive", exception.getMessage());
    }
}
