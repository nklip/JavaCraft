package dev.nklip.javacraft.xlspaceship.server;

import dev.nklip.javacraft.xlspaceship.engine.service.RandomProvider;
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
    public RandomProvider randomService() {
        RandomProvider randomProvider = mock(RandomProvider.class);

        when(randomProvider.generateAI()).thenReturn(1000);
        when(randomProvider.generatePlayer()).thenReturn(1);
        when(randomProvider.generateForm()).thenReturn(1);
        when(randomProvider.generateUp10()).thenReturn(3,6, 9);
        when(randomProvider.generateUp16()).thenReturn(0);
        when(randomProvider.generateCell(anyInt())).thenReturn(// x, y
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

        return randomProvider;
    }

}
