package dev.nklip.javacraft.shardshop.sharding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShardTest {

    @Test
    void keepsTheClusterName() {
        assertEquals("shard-a", new Shard("shard-a").clusterName());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void rejectsBlankClusterNames(String name) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new Shard(name));

        assertEquals("Shard name must not be blank", exception.getMessage());
    }

    @Test
    void clusterNameIsItsIdentity() {
        assertEquals(new Shard("shard-a"), new Shard("shard-a"));
    }
}
