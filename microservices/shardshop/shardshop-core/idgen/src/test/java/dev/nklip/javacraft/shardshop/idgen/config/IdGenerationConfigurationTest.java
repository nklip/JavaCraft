package dev.nklip.javacraft.shardshop.idgen.config;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class IdGenerationConfigurationTest {

    private static final String RESERVED_ID = "shardshop.launcher.reserved-generator-id";
    private final IdConfiguration configuration = mock(IdConfiguration.class);
    private final IdGenerationConfiguration producer = new IdGenerationConfiguration();
    private String previousReservation;
    private String reservation;

    @BeforeEach
    void captureReservation() {
        previousReservation = System.clearProperty(RESERVED_ID);
    }

    @AfterEach
    void restoreReservation() {
        if (previousReservation == null) {
            assertEquals(reservation, System.clearProperty(RESERVED_ID));
        } else {
            assertEquals(reservation, System.setProperty(RESERVED_ID, previousReservation));
        }
    }

    @Test
    void directSmokeLaunchesWithoutAReservationStillUseTheConfiguredId() {
        when(configuration.generatorId()).thenReturn(7L);

        assertGeneratorId(7, producer.idGenerator(configuration));
    }

    @Test
    void matchingReservationAllowsTheConfiguredId() {
        reserve("7");
        when(configuration.generatorId()).thenReturn(7L);

        assertGeneratorId(7, producer.idGenerator(configuration));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "", "not-a-number", "07", "+7", "7\n"})
    void effectiveConfigurationCannotReplaceTheRawReservedIdentity(String reservedId) {
        reserve(reservedId);
        when(configuration.generatorId()).thenReturn(7L);

        assertEquals("Live generator ID does not match its reserved identity",
                assertThrows(IllegalStateException.class, () -> producer.idGenerator(configuration)).getMessage());
    }

    private void reserve(String reservedId) {
        reservation = reservedId;
        assertNull(System.setProperty(RESERVED_ID, reservedId));
    }

    private static void assertGeneratorId(long expected, IdGenerator generator) {
        assertEquals(expected, (generator.nextId() >>> 12) & 1023);
    }
}
