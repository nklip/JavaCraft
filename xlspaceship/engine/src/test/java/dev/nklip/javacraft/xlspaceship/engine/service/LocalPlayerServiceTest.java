package dev.nklip.javacraft.xlspaceship.engine.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class LocalPlayerServiceTest {

    @Test
    public void testConstructorDefaults() {
        RandomProvider randomProvider = Mockito.mock(RandomProvider.class);
        LocalPlayerService localPlayerService = new LocalPlayerService(randomProvider);

        Assertions.assertSame(randomProvider, localPlayerService.getRandomProvider());
        Assertions.assertNull(localPlayerService.getUserId());
        Assertions.assertNull(localPlayerService.getFullName());
        Assertions.assertFalse(localPlayerService.isAI());
    }

    @Test
    public void testSetUpAI() {
        RandomProvider randomProvider = Mockito.mock(RandomProvider.class);
        Mockito.when(randomProvider.generateAI()).thenReturn(42);

        LocalPlayerService localPlayerService = new LocalPlayerService(randomProvider);
        localPlayerService.setUpAI();

        Assertions.assertTrue(localPlayerService.isAI());
        Assertions.assertEquals("AI", localPlayerService.getUserId());
        Assertions.assertEquals("AI-42", localPlayerService.getFullName());
        Mockito.verify(randomProvider).generateAI();
    }

}
