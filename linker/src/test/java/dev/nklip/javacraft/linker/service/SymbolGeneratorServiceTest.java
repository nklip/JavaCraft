package dev.nklip.javacraft.linker.service;

import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SymbolGeneratorServiceTest {

    @Test
    public void testGeneratedSymbolsShouldAlwaysBeAlphaNumeric() {
        for (int i = 0; i < 1000; i++) {
            char symbol = SymbolGeneratorService.generateSymbol();
            Assertions.assertTrue(Character.isLetterOrDigit(symbol));
        }
    }

    @Test
    public void testGenerateShortTextShouldRespectLength() {
        String generated = SymbolGeneratorService.generateShortText(12);
        Assertions.assertEquals(12, generated.length());
    }

    @Test
    public void testGenerateShortTextShouldBeDeterministicWithProvidedRandom() {
        Random random = new Random(42L);
        String generated = SymbolGeneratorService.generateShortText(6, random);
        Assertions.assertEquals(6, generated.length());
        Assertions.assertEquals("Gpi2C7", generated);
    }

    @Test
    public void testGenerateShortTextShouldRejectNonPositiveLength() {
        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SymbolGeneratorService.generateShortText(0)
        );
        Assertions.assertEquals("length must be positive", exception.getMessage());
    }
}
