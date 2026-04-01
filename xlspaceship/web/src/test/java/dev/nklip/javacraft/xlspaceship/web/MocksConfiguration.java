package dev.nklip.javacraft.xlspaceship.web;

import dev.nklip.javacraft.xlspaceship.impl.service.RandomServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import static org.mockito.Mockito.*;

@Profile("test")
@Configuration
public class MocksConfiguration {

    @Bean
    @Primary
    public RandomServices randomService() {
        RandomServices randomServices = mock(RandomServices.class);

        when(randomServices.generateAI()).thenReturn(1000);
        when(randomServices.generatePlayer()).thenReturn(1);
        when(randomServices.generateForm()).thenReturn(1);
        when(randomServices.generateUp10()).thenReturn(3,6, 9);
        when(randomServices.generateCell(anyInt())).thenReturn(// x, y
                // opponent's ships
                0, 0, // the first ship
                0, 6, // the second ship
                7, 0, // the third ship
                7, 7, // the fourth ship
                12, 12, // the fifth ship

                // my ships
                3, 0, // the first ship
                3, 6, // the second ship
                10, 0, // the third ship
                10, 7, // the fourth ship
                7, 12 // the fifth ship
        );

        return randomServices;
    }

}
