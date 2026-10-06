package dev.nklip.javacraft.shardshop.idgen.config;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IdGeneratorInjectionTest {

    @Inject
    IdConfiguration configuration;

    @Inject
    IdGenerator generator;

    @Inject
    IdGenerator anotherInjection;

    @Test
    void injectsOneLiveGeneratorForTheProcess() {
        assertEquals(1L, configuration.generatorId());
        assertSame(generator, anotherInjection);
        long first = generator.nextId();
        long second = anotherInjection.nextId();

        assertTrue(first > 0);
        assertTrue(second > first);
        assertEquals(1L, (first >>> 12) & 1023);
        assertEquals(1L, (second >>> 12) & 1023);
    }
}
