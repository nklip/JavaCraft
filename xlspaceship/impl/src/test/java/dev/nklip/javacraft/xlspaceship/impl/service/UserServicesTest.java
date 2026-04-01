package dev.nklip.javacraft.xlspaceship.impl.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class UserServicesTest {

    @Test
    public void testConstructorDefaults() {
        RandomServices randomServices = Mockito.mock(RandomServices.class);
        UserServices userServices = new UserServices(randomServices);

        Assertions.assertSame(randomServices, userServices.getRandomServices());
        Assertions.assertNull(userServices.getUserId());
        Assertions.assertNull(userServices.getFullName());
        Assertions.assertFalse(userServices.isAI());
    }

    @Test
    public void testSetUpAI() {
        RandomServices randomServices = Mockito.mock(RandomServices.class);
        Mockito.when(randomServices.generateAI()).thenReturn(42);

        UserServices userServices = new UserServices(randomServices);
        userServices.setUpAI();

        Assertions.assertTrue(userServices.isAI());
        Assertions.assertEquals("AI", userServices.getUserId());
        Assertions.assertEquals("AI-42", userServices.getFullName());
        Mockito.verify(randomServices).generateAI();
    }

}
